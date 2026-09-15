package com.roomflow.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.roomflow.common.enums.Role;
import com.roomflow.security.LoginAccount;
import com.roomflow.security.SessionTokenStore;
import com.roomflow.security.SessionTokenStore.SessionInfo;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Redis-backed session store: create / lookup / revoke / refresh rotation on real Redis 8.2. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class SessionTokenStoreIT extends AbstractContainersIT {

  @Autowired private SessionTokenStore store;

  private static final LoginAccount ALICE = new LoginAccount(1001L, "it-alice", Role.USER);
  private static final Duration TTL = Duration.ofDays(30);

  @Test
  void createThenActiveThenRevoke() {
    SessionInfo session = store.createSession(ALICE, TTL);
    assertTrue(store.isSessionActive(session.sessionId()));
    assertTrue(store.findByRefreshToken(session.refreshToken()).isPresent());

    store.revoke(session.sessionId());
    assertFalse(store.isSessionActive(session.sessionId()));
    assertTrue(store.findByRefreshToken(session.refreshToken()).isEmpty());
  }

  @Test
  void rotateInvalidatesOldTokenAndSession() {
    SessionInfo first = store.createSession(ALICE, TTL);
    SessionInfo second = store.rotate(first.refreshToken(), ALICE, TTL);

    assertNotEquals(first.sessionId(), second.sessionId());
    assertNotEquals(first.refreshToken(), second.refreshToken());
    // Old refresh token and old access-token session are both dead (rotation).
    assertTrue(store.findByRefreshToken(first.refreshToken()).isEmpty());
    assertFalse(store.isSessionActive(first.sessionId()));
    assertTrue(store.isSessionActive(second.sessionId()));
    assertEquals(1001L, store.findByRefreshToken(second.refreshToken()).get().accountId());
  }

  @Test
  void unknownRefreshTokenIsEmpty() {
    assertTrue(store.findByRefreshToken("never-issued").isEmpty());
  }
}
