package com.roomflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roomflow.common.enums.Role;
import com.roomflow.common.exception.BizException;
import com.roomflow.common.exception.ErrorCode;
import com.roomflow.domain.account.Account;
import com.roomflow.domain.account.AccountVO;
import com.roomflow.domain.account.AuthTokenVO;
import com.roomflow.domain.account.LoginRequest;
import com.roomflow.domain.account.RegisterRequest;
import com.roomflow.mapper.AccountMapper;
import com.roomflow.security.JwtTokenProvider;
import com.roomflow.security.LoginAccount;
import com.roomflow.security.SessionTokenStore;
import com.roomflow.security.SessionTokenStore.SessionInfo;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Authentication domain service: register/login/logout/refresh/me. */
@Service
public class AccountService {

  private static final Logger log = LoggerFactory.getLogger(AccountService.class);
  private static final int STATUS_ENABLED = 1;

  private final AccountMapper accountMapper;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenProvider tokenProvider;
  private final SessionTokenStore sessionTokenStore;
  private final Clock clock;

  public AccountService(
      AccountMapper accountMapper,
      PasswordEncoder passwordEncoder,
      JwtTokenProvider tokenProvider,
      SessionTokenStore sessionTokenStore,
      Clock clock) {
    this.accountMapper = accountMapper;
    this.passwordEncoder = passwordEncoder;
    this.tokenProvider = tokenProvider;
    this.sessionTokenStore = sessionTokenStore;
    this.clock = clock;
  }

  /**
   * Registers a USER account; registration doubles as login and returns a token pair. Deliberately
   * NOT transactional: the only DB write is a single INSERT (atomic on its own), and the Redis
   * session is created strictly AFTER the row is committed — so a commit failure can never leave an
   * orphan session, and a session-write failure can never roll the account back (the user can
   * simply log in afterwards).
   */
  public AuthTokenVO register(RegisterRequest request) {
    Long existing =
        accountMapper.selectCount(
            new LambdaQueryWrapper<Account>().eq(Account::getUsername, request.getUsername()));
    if (existing != null && existing > 0) {
      throw new BizException(ErrorCode.USERNAME_EXISTS);
    }
    Account account = new Account();
    account.setUsername(request.getUsername());
    account.setPasswordHash(passwordEncoder.encode(request.getPassword()));
    account.setRole(Role.USER);
    account.setStatus(STATUS_ENABLED);
    try {
      accountMapper.insert(account);
    } catch (DuplicateKeyException e) {
      // Unique-key race: two concurrent registrations of the same username.
      throw new BizException(ErrorCode.USERNAME_EXISTS);
    }
    return issueTokens(toLogin(account));
  }

  /** Verifies credentials; unknown user and wrong password both return 40103 (anti-enumeration). */
  public AuthTokenVO login(LoginRequest request) {
    Account account =
        accountMapper.selectOne(
            new LambdaQueryWrapper<Account>().eq(Account::getUsername, request.getUsername()));
    if (account == null
        || !passwordEncoder.matches(request.getPassword(), account.getPasswordHash())) {
      throw new BizException(ErrorCode.BAD_CREDENTIALS);
    }
    if (account.getStatus() == null || account.getStatus() != STATUS_ENABLED) {
      throw new BizException(ErrorCode.ACCOUNT_DISABLED);
    }
    return issueTokens(toLogin(account));
  }

  /** Logout revokes the server-side session: both the access token (jti) and refresh token die. */
  public void logout(String sessionId) {
    sessionTokenStore.revoke(sessionId);
  }

  /** Rotates the refresh token: the presented token and its session are invalidated immediately. */
  public AuthTokenVO refresh(String refreshToken) {
    SessionInfo session =
        sessionTokenStore
            .findByRefreshToken(refreshToken)
            .orElseThrow(() -> new BizException(ErrorCode.REFRESH_INVALID));
    Account account = accountMapper.selectById(session.accountId());
    if (account == null || account.getStatus() == null || account.getStatus() != STATUS_ENABLED) {
      sessionTokenStore.revoke(session.sessionId());
      throw new BizException(ErrorCode.REFRESH_INVALID);
    }
    LoginAccount loginAccount = toLogin(account);
    SessionInfo rotated =
        sessionTokenStore.rotate(refreshToken, loginAccount, tokenProvider.getRefreshTtl());
    return toTokenVO(loginAccount, rotated);
  }

  public AccountVO me(LoginAccount current) {
    Account account = accountMapper.selectById(current.id());
    if (account == null) {
      throw new BizException(ErrorCode.UNAUTHORIZED);
    }
    return AccountVO.from(account);
  }

  private AuthTokenVO issueTokens(LoginAccount account) {
    SessionInfo session = sessionTokenStore.createSession(account, tokenProvider.getRefreshTtl());
    return toTokenVO(account, session);
  }

  private AuthTokenVO toTokenVO(LoginAccount account, SessionInfo session) {
    AuthTokenVO vo = new AuthTokenVO();
    vo.setAccessToken(
        tokenProvider.createAccessToken(account, session.sessionId(), Instant.now(clock)));
    vo.setAccessTokenExpiresIn(tokenProvider.getAccessTtl().toSeconds());
    vo.setRefreshToken(session.refreshToken());
    vo.setRefreshTokenExpiresIn(tokenProvider.getRefreshTtl().toSeconds());
    return vo;
  }

  private static LoginAccount toLogin(Account account) {
    return new LoginAccount(account.getId(), account.getUsername(), account.getRole());
  }
}
