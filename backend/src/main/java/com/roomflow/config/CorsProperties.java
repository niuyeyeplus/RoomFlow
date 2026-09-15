package com.roomflow.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Allowed CORS origins for /api/**. Bound per profile: dev lists the Vite dev server; staging/prod
 * inject CORS_ALLOWED_ORIGINS (comma-separated) with NO default, so a missing value binds an empty
 * list and rejects every cross-origin request instead of falling back to a wildcard.
 */
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

  private List<String> allowedOrigins = new ArrayList<>();

  public List<String> getAllowedOrigins() {
    return allowedOrigins;
  }

  public void setAllowedOrigins(List<String> allowedOrigins) {
    this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : allowedOrigins;
  }
}
