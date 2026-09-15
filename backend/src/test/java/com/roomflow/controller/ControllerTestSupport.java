package com.roomflow.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.roomflow.common.enums.Role;
import com.roomflow.security.JwtTokenProvider;
import com.roomflow.security.LoginAccount;
import com.roomflow.security.SessionTokenStore;
import com.roomflow.security.UserDetailsServiceImpl;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Shared wiring for @WebMvcTest slices: a mocked token pipeline where specific bearer strings map
 * to known principals, so tests exercise the real SecurityConfig filter chain end to end.
 */
public abstract class ControllerTestSupport {

  protected static final String USER_TOKEN = "user-token";
  protected static final String ADMIN_TOKEN = "admin-token";
  protected static final String REVOKED_TOKEN = "revoked-token";
  protected static final String EXPIRED_TOKEN = "expired-token";
  protected static final String BAD_TOKEN = "bad-token";

  protected static final LoginAccount USER = new LoginAccount(1L, "alice", Role.USER);
  protected static final LoginAccount ADMIN = new LoginAccount(9L, "admin", Role.ADMIN);

  @MockitoBean protected JwtTokenProvider tokenProvider;
  @MockitoBean protected SessionTokenStore sessionTokenStore;
  @MockitoBean protected UserDetailsServiceImpl userDetailsService;

  @BeforeEach
  void setUpSecurity() {
    Claims userClaims = claims("s-user", "1");
    Claims adminClaims = claims("s-admin", "9");
    Claims deadClaims = claims("s-dead", "1");
    when(tokenProvider.parse(USER_TOKEN)).thenReturn(userClaims);
    when(tokenProvider.parse(ADMIN_TOKEN)).thenReturn(adminClaims);
    when(tokenProvider.parse(REVOKED_TOKEN)).thenReturn(deadClaims);
    when(tokenProvider.parse(EXPIRED_TOKEN))
        .thenThrow(new ExpiredJwtException(null, null, "expired"));
    when(tokenProvider.parse(BAD_TOKEN)).thenThrow(new JwtException("bad signature"));

    when(sessionTokenStore.isSessionActive("s-user")).thenReturn(true);
    when(sessionTokenStore.isSessionActive("s-admin")).thenReturn(true);
    when(sessionTokenStore.isSessionActive("s-dead")).thenReturn(false);

    when(userDetailsService.loadById(1L)).thenReturn(USER);
    when(userDetailsService.loadById(9L)).thenReturn(ADMIN);
  }

  private static Claims claims(String sessionId, String subject) {
    Claims claims = mock(Claims.class);
    when(claims.getId()).thenReturn(sessionId);
    when(claims.getSubject()).thenReturn(subject);
    when(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class))
        .thenReturn(JwtTokenProvider.TYPE_ACCESS);
    return claims;
  }

  protected static String bearer(String token) {
    return "Bearer " + token;
  }
}
