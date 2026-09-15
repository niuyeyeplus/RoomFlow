package com.roomflow.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roomflow.common.enums.Role;
import com.roomflow.security.LoginAccount;
import com.roomflow.security.SessionTokenStore;
import com.roomflow.security.SessionTokenStore.SessionInfo;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class SessionTokenStoreTest {

  @Mock private StringRedisTemplate redis;
  @Mock private ValueOperations<String, String> ops;

  private SessionTokenStore store;

  @BeforeEach
  void setUp() {
    org.mockito.Mockito.lenient().when(redis.opsForValue()).thenReturn(ops);
    store = new SessionTokenStore(redis, new ObjectMapper());
  }

  @Test
  void createSessionWritesSessionAndRefreshKeys() {
    SessionInfo info =
        store.createSession(new LoginAccount(1L, "alice", Role.USER), Duration.ofDays(30));
    verify(ops)
        .set(eq("roomflow:session:" + info.sessionId()), anyString(), eq(Duration.ofDays(30)));
    verify(ops)
        .set(
            eq("roomflow:refresh:" + info.refreshToken()),
            eq(info.sessionId()),
            eq(Duration.ofDays(30)));
  }

  @Test
  void isSessionActiveChecksKey() {
    when(redis.hasKey("roomflow:session:sid-1")).thenReturn(true);
    assertTrue(store.isSessionActive("sid-1"));
    when(redis.hasKey("roomflow:session:sid-2")).thenReturn(false);
    assertFalse(store.isSessionActive("sid-2"));
    assertFalse(store.isSessionActive(null));
  }

  @Test
  void findByRefreshTokenReturnsSession() {
    SessionInfo info = new SessionInfo("sid-1", 1L, "alice", "USER", "rt-1");
    when(ops.get("roomflow:refresh:rt-1")).thenReturn("sid-1");
    when(ops.get("roomflow:session:sid-1"))
        .thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(info).toString());
    Optional<SessionInfo> found = store.findByRefreshToken("rt-1");
    assertTrue(found.isPresent());
    assertEquals(1L, found.get().accountId());
  }

  @Test
  void findByRefreshTokenEmptyWhenMissing() {
    when(ops.get("roomflow:refresh:ghost")).thenReturn(null);
    assertTrue(store.findByRefreshToken("ghost").isEmpty());
  }

  @Test
  void findByRefreshTokenCleansOrphanRefreshKey() {
    when(ops.get("roomflow:refresh:rt-1")).thenReturn("sid-dead");
    when(ops.get("roomflow:session:sid-dead")).thenReturn(null);
    assertTrue(store.findByRefreshToken("rt-1").isEmpty());
    verify(redis).delete("roomflow:refresh:rt-1");
  }

  @Test
  void rotateDeletesOldKeysAndCreatesNew() {
    SessionInfo old = new SessionInfo("sid-old", 1L, "alice", "USER", "rt-old");
    when(ops.get("roomflow:refresh:rt-old")).thenReturn("sid-old");
    when(ops.get("roomflow:session:sid-old"))
        .thenReturn(new ObjectMapper().valueToTree(old).toString());

    SessionInfo rotated =
        store.rotate("rt-old", new LoginAccount(1L, "alice", Role.USER), Duration.ofDays(30));

    verify(redis).delete("roomflow:session:sid-old");
    verify(redis).delete("roomflow:refresh:rt-old");
    assertTrue(rotated.sessionId() != null && !rotated.sessionId().equals("sid-old"));
    assertTrue(rotated.refreshToken() != null && !rotated.refreshToken().equals("rt-old"));
  }

  @Test
  void revokeDeletesSessionAndRefreshToken() {
    SessionInfo info = new SessionInfo("sid-1", 1L, "alice", "USER", "rt-1");
    when(ops.get("roomflow:session:sid-1"))
        .thenReturn(new ObjectMapper().valueToTree(info).toString());
    store.revoke("sid-1");
    verify(redis).delete("roomflow:session:sid-1");
    verify(redis).delete("roomflow:refresh:rt-1");
  }

  @Test
  void revokeNullOrMissingIsNoop() {
    store.revoke(null);
    verify(redis, never()).delete(anyString());
    when(ops.get("roomflow:session:sid-x")).thenReturn(null);
    store.revoke("sid-x");
    verify(redis).delete("roomflow:session:sid-x");
    verify(redis, never()).delete("roomflow:refresh:rt-x");
  }
}
