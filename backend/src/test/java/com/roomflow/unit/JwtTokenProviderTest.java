package com.roomflow.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.roomflow.common.enums.Role;
import com.roomflow.config.JwtProperties;
import com.roomflow.security.JwtTokenProvider;
import com.roomflow.security.LoginAccount;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

  private static JwtTokenProvider provider(Duration accessTtl) {
    JwtProperties props = new JwtProperties();
    props.setSecret("unit-test-jwt-secret-key-0123456789abcdef");
    props.setAccessTtl(accessTtl);
    props.setRefreshTtl(Duration.ofDays(30));
    return new JwtTokenProvider(props);
  }

  @Test
  void issuesAndParsesAccessToken() {
    JwtTokenProvider provider = provider(Duration.ofMinutes(15));
    // Issue with the real clock: JJWT parses expiry against wall time, so a fixed
    // Instant in the past makes the token stale as soon as the TTL elapses.
    String token =
        provider.createAccessToken(
            new LoginAccount(1L, "alice", Role.USER), "sid-1", Instant.now());
    Claims claims = provider.parse(token);
    assertEquals("1", claims.getSubject());
    assertEquals("sid-1", claims.getId());
    assertEquals("access", claims.get(JwtTokenProvider.CLAIM_TYPE, String.class));
    assertEquals("USER", claims.get("role", String.class));
  }

  @Test
  void rejectsTamperedToken() {
    JwtTokenProvider provider = provider(Duration.ofMinutes(15));
    String token =
        provider.createAccessToken(
            new LoginAccount(1L, "alice", Role.USER), "sid-1", Instant.now());
    assertThrows(JwtException.class, () -> provider.parse(token + "tampered"));
  }

  @Test
  void rejectsTokenSignedWithDifferentSecret() {
    JwtTokenProvider provider = provider(Duration.ofMinutes(15));
    JwtProperties other = new JwtProperties();
    other.setSecret("another-secret-key-0123456789abcdef0000");
    other.setAccessTtl(Duration.ofMinutes(15));
    String foreign =
        new JwtTokenProvider(other)
            .createAccessToken(new LoginAccount(1L, "alice", Role.USER), "sid-1", Instant.now());
    assertThrows(JwtException.class, () -> provider.parse(foreign));
  }

  @Test
  void rejectsExpiredToken() {
    // Negative TTL issues an already-expired token; parsing must raise ExpiredJwtException.
    JwtTokenProvider provider = provider(Duration.ofSeconds(-10));
    String token =
        provider.createAccessToken(
            new LoginAccount(1L, "alice", Role.USER), "sid-1", Instant.now());
    assertThrows(ExpiredJwtException.class, () -> provider.parse(token));
  }

  @Test
  void rejectsShortSecretAtStartup() {
    JwtProperties props = new JwtProperties();
    props.setSecret("too-short");
    assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(props));
  }

  @Test
  void rejectsMissingSecretAtStartup() {
    assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(new JwtProperties()));
  }

  @Test
  void exposesTtls() {
    JwtTokenProvider provider = provider(Duration.ofMinutes(15));
    assertEquals(Duration.ofMinutes(15), provider.getAccessTtl());
    assertEquals(Duration.ofDays(30), provider.getRefreshTtl());
    assertNotNull(provider);
  }
}
