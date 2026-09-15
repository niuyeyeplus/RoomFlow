package com.roomflow.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed server-side session store. Each session holds an opaque refresh token; the access
 * token's jti equals the session id, so deleting the session revokes the access token immediately.
 */
@Component
public class SessionTokenStore {

  private static final String SESSION_PREFIX = "roomflow:session:";
  private static final String REFRESH_PREFIX = "roomflow:refresh:";

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  public SessionTokenStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
    this.redis = redis;
    this.objectMapper = objectMapper;
  }

  public record SessionInfo(
      String sessionId, Long accountId, String username, String role, String refreshToken) {}

  /** Creates a new session + refresh token pair with the given TTL (sliding 30d window). */
  public SessionInfo createSession(LoginAccount account, Duration ttl) {
    String sessionId = UUID.randomUUID().toString();
    String refreshToken = UUID.randomUUID().toString().replace("-", "");
    SessionInfo info =
        new SessionInfo(
            sessionId, account.id(), account.username(), account.role().name(), refreshToken);
    redis.opsForValue().set(SESSION_PREFIX + sessionId, write(info), ttl);
    redis.opsForValue().set(REFRESH_PREFIX + refreshToken, sessionId, ttl);
    return info;
  }

  public boolean isSessionActive(String sessionId) {
    return sessionId != null && Boolean.TRUE.equals(redis.hasKey(SESSION_PREFIX + sessionId));
  }

  public Optional<SessionInfo> findByRefreshToken(String refreshToken) {
    String sessionId = redis.opsForValue().get(REFRESH_PREFIX + refreshToken);
    if (sessionId == null) {
      return Optional.empty();
    }
    String json = redis.opsForValue().get(SESSION_PREFIX + sessionId);
    if (json == null) {
      redis.delete(REFRESH_PREFIX + refreshToken);
      return Optional.empty();
    }
    return Optional.of(read(json));
  }

  /**
   * Rotates a refresh token: the old refresh token and old session are invalidated immediately. The
   * delete+create is not atomic and reuse of an already-rotated token is not detected — both are
   * accepted trade-offs for this slice; refresh-token reuse detection (theft signal) is on the
   * security backlog.
   */
  public SessionInfo rotate(String oldRefreshToken, LoginAccount account, Duration ttl) {
    findByRefreshToken(oldRefreshToken)
        .ifPresent(old -> redis.delete(SESSION_PREFIX + old.sessionId()));
    redis.delete(REFRESH_PREFIX + oldRefreshToken);
    return createSession(account, ttl);
  }

  /** Revokes a session (logout / account disabled): deletes session and its refresh token. */
  public void revoke(String sessionId) {
    if (sessionId == null) {
      return;
    }
    String json = redis.opsForValue().get(SESSION_PREFIX + sessionId);
    if (json != null) {
      redis.delete(REFRESH_PREFIX + read(json).refreshToken());
    }
    redis.delete(SESSION_PREFIX + sessionId);
  }

  private String write(SessionInfo info) {
    try {
      return objectMapper.writeValueAsString(info);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize session", e);
    }
  }

  private SessionInfo read(String json) {
    try {
      return objectMapper.readValue(json, SessionInfo.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to deserialize session", e);
    }
  }
}
