package com.roomflow.security;

import com.roomflow.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/** Issues and validates HS256 JWT access tokens. Refresh tokens are opaque and live in Redis. */
@Component
public class JwtTokenProvider {

  public static final String CLAIM_TYPE = "typ";
  public static final String TYPE_ACCESS = "access";
  private static final int MIN_SECRET_BYTES = 32;

  private final JwtProperties properties;
  private final SecretKey key;

  public JwtTokenProvider(JwtProperties properties) {
    this.properties = properties;
    byte[] secret =
        properties.getSecret() == null
            ? new byte[0]
            : properties.getSecret().getBytes(StandardCharsets.UTF_8);
    if (secret.length < MIN_SECRET_BYTES) {
      // Fail fast: staging/prod must start with a proper secret or not start at all.
      throw new IllegalStateException("roomflow.jwt.secret must be at least 32 bytes");
    }
    this.key = Keys.hmacShaKeyFor(secret);
  }

  /** Creates an access token whose jti is the server-side session id (revocable via Redis). */
  public String createAccessToken(LoginAccount account, String sessionId, Instant now) {
    return Jwts.builder()
        .subject(String.valueOf(account.id()))
        .id(sessionId)
        .claim(CLAIM_TYPE, TYPE_ACCESS)
        .claim("username", account.username())
        .claim("role", account.role().name())
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(properties.getAccessTtl())))
        .signWith(key)
        .compact();
  }

  /**
   * Parses and validates signature + expiry.
   *
   * @throws ExpiredJwtException when the token is well-formed but expired
   * @throws JwtException on any other validation failure (bad signature, malformed, ...)
   */
  public Claims parse(String token) {
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  }

  public Duration getAccessTtl() {
    return properties.getAccessTtl();
  }

  public Duration getRefreshTtl() {
    return properties.getRefreshTtl();
  }
}
