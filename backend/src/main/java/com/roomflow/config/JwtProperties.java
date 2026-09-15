package com.roomflow.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "roomflow.jwt")
public class JwtProperties {

  /** HS256 secret; must be at least 32 bytes. Injected via ROOMFLOW_JWT_SECRET env var. */
  private String secret;

  private Duration accessTtl = Duration.ofMinutes(15);
  private Duration refreshTtl = Duration.ofDays(30);
}
