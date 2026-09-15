package com.roomflow.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roomflow.common.enums.Role;
import com.roomflow.common.exception.BizException;
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
import com.roomflow.service.AccountService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

  @Mock private AccountMapper accountMapper;
  @Mock private SessionTokenStore sessionTokenStore;

  private AccountService service;
  private JwtTokenProvider tokenProvider;
  private PasswordEncoder passwordEncoder;

  @BeforeEach
  void setUp() {
    passwordEncoder = new BCryptPasswordEncoder();
    com.roomflow.config.JwtProperties props = new com.roomflow.config.JwtProperties();
    props.setSecret("unit-test-jwt-secret-key-0123456789abcdef");
    props.setAccessTtl(Duration.ofMinutes(15));
    props.setRefreshTtl(Duration.ofDays(30));
    tokenProvider = new JwtTokenProvider(props);
    service =
        new AccountService(
            accountMapper,
            passwordEncoder,
            tokenProvider,
            sessionTokenStore,
            Clock.fixed(Instant.parse("2026-09-15T08:00:00Z"), ZoneId.of("Asia/Shanghai")));
  }

  private static SessionInfo session(String sid, Long accountId, String refreshToken) {
    return new SessionInfo(sid, accountId, "alice", "USER", refreshToken);
  }

  private void stubSessionCreation() {
    when(sessionTokenStore.createSession(any(), eq(Duration.ofDays(30))))
        .thenReturn(session("sid-1", 1L, "rt-1"));
  }

  private static int code(BizException e) {
    return e.getErrorCode().getCode();
  }

  @Test
  void registerSucceedsAndIssuesTokens() {
    when(accountMapper.selectCount(any())).thenReturn(0L);
    stubSessionCreation();
    RegisterRequest req = new RegisterRequest();
    req.setUsername("alice");
    req.setPassword("password123");

    AuthTokenVO vo = service.register(req);

    verify(accountMapper).insert(any(Account.class));
    assertNotNull(vo.getAccessToken());
    assertEquals("rt-1", vo.getRefreshToken());
    assertEquals(900, vo.getAccessTokenExpiresIn());
    assertEquals(2592000, vo.getRefreshTokenExpiresIn());
    assertEquals("Bearer", vo.getTokenType());
  }

  @Test
  void registerRejectsDuplicateUsername() {
    when(accountMapper.selectCount(any())).thenReturn(1L);
    RegisterRequest req = new RegisterRequest();
    req.setUsername("alice");
    req.setPassword("password123");
    BizException e = assertThrows(BizException.class, () -> service.register(req));
    assertEquals(40901, code(e));
  }

  @Test
  void registerMapsUniqueKeyRaceTo40901() {
    when(accountMapper.selectCount(any())).thenReturn(0L);
    when(accountMapper.insert(any(Account.class))).thenThrow(new DuplicateKeyException("dup"));
    RegisterRequest req = new RegisterRequest();
    req.setUsername("alice");
    req.setPassword("password123");
    BizException e = assertThrows(BizException.class, () -> service.register(req));
    assertEquals(40901, code(e));
  }

  @Test
  void registerStoresBcryptHash() {
    when(accountMapper.selectCount(any())).thenReturn(0L);
    stubSessionCreation();
    RegisterRequest req = new RegisterRequest();
    req.setUsername("alice");
    req.setPassword("password123");
    service.register(req);
    org.mockito.ArgumentCaptor<Account> captor = org.mockito.ArgumentCaptor.forClass(Account.class);
    verify(accountMapper).insert(captor.capture());
    String hash = captor.getValue().getPasswordHash();
    assertNotNull(hash);
    org.junit.jupiter.api.Assertions.assertTrue(hash.startsWith("$2"));
    org.junit.jupiter.api.Assertions.assertTrue(passwordEncoder.matches("password123", hash));
  }

  @Test
  void loginRejectsUnknownUser() {
    when(accountMapper.selectOne(any())).thenReturn(null);
    LoginRequest req = new LoginRequest();
    req.setUsername("ghost");
    req.setPassword("password123");
    BizException e = assertThrows(BizException.class, () -> service.login(req));
    assertEquals(40103, code(e));
  }

  @Test
  void loginRejectsWrongPassword() {
    Account account = new Account();
    account.setId(1L);
    account.setUsername("alice");
    account.setPasswordHash(passwordEncoder.encode("right-password"));
    account.setRole(Role.USER);
    account.setStatus(1);
    when(accountMapper.selectOne(any())).thenReturn(account);
    LoginRequest req = new LoginRequest();
    req.setUsername("alice");
    req.setPassword("wrong-password");
    BizException e = assertThrows(BizException.class, () -> service.login(req));
    assertEquals(40103, code(e));
  }

  @Test
  void loginRejectsDisabledAccount() {
    Account account = new Account();
    account.setId(1L);
    account.setUsername("alice");
    account.setPasswordHash(passwordEncoder.encode("password123"));
    account.setRole(Role.USER);
    account.setStatus(0);
    when(accountMapper.selectOne(any())).thenReturn(account);
    LoginRequest req = new LoginRequest();
    req.setUsername("alice");
    req.setPassword("password123");
    BizException e = assertThrows(BizException.class, () -> service.login(req));
    assertEquals(40302, code(e));
    verify(sessionTokenStore, never()).createSession(any(), any());
  }

  @Test
  void loginSucceeds() {
    Account account = new Account();
    account.setId(1L);
    account.setUsername("alice");
    account.setPasswordHash(passwordEncoder.encode("password123"));
    account.setRole(Role.USER);
    account.setStatus(1);
    when(accountMapper.selectOne(any())).thenReturn(account);
    stubSessionCreation();
    LoginRequest req = new LoginRequest();
    req.setUsername("alice");
    req.setPassword("password123");
    AuthTokenVO vo = service.login(req);
    assertEquals("rt-1", vo.getRefreshToken());
  }

  @Test
  void refreshRejectsInvalidToken() {
    when(sessionTokenStore.findByRefreshToken("bad")).thenReturn(Optional.empty());
    BizException e = assertThrows(BizException.class, () -> service.refresh("bad"));
    assertEquals(40104, code(e));
  }

  @Test
  void refreshRotatesAndRevokesOldSession() {
    when(sessionTokenStore.findByRefreshToken("rt-old"))
        .thenReturn(Optional.of(session("sid-old", 1L, "rt-old")));
    Account account = new Account();
    account.setId(1L);
    account.setUsername("alice");
    account.setRole(Role.USER);
    account.setStatus(1);
    when(accountMapper.selectById(1L)).thenReturn(account);
    when(sessionTokenStore.rotate(eq("rt-old"), any(), eq(Duration.ofDays(30))))
        .thenReturn(session("sid-new", 1L, "rt-new"));

    AuthTokenVO vo = service.refresh("rt-old");

    assertEquals("rt-new", vo.getRefreshToken());
    verify(sessionTokenStore).rotate(eq("rt-old"), any(), eq(Duration.ofDays(30)));
  }

  @Test
  void refreshRevokesAndRejectsWhenAccountDisabled() {
    when(sessionTokenStore.findByRefreshToken("rt-old"))
        .thenReturn(Optional.of(session("sid-old", 1L, "rt-old")));
    Account account = new Account();
    account.setId(1L);
    account.setStatus(0);
    when(accountMapper.selectById(1L)).thenReturn(account);
    BizException e = assertThrows(BizException.class, () -> service.refresh("rt-old"));
    assertEquals(40104, code(e));
    verify(sessionTokenStore).revoke("sid-old");
  }

  @Test
  void logoutRevokesSession() {
    service.logout("sid-1");
    verify(sessionTokenStore).revoke("sid-1");
  }

  @Test
  void meReturnsAccount() {
    Account account = new Account();
    account.setId(1L);
    account.setUsername("alice");
    account.setRole(Role.USER);
    account.setStatus(1);
    when(accountMapper.selectById(1L)).thenReturn(account);
    AccountVO vo = service.me(new LoginAccount(1L, "alice", Role.USER));
    assertEquals("alice", vo.getUsername());
    assertEquals(Role.USER, vo.getRole());
  }

  @Test
  void meRejectsMissingAccount() {
    when(accountMapper.selectById(1L)).thenReturn(null);
    BizException e =
        assertThrows(
            BizException.class, () -> service.me(new LoginAccount(1L, "alice", Role.USER)));
    assertEquals(40101, code(e));
  }
}
