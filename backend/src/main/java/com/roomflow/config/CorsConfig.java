package com.roomflow.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuration-driven CORS for /api/**. Origins come from app.cors.allowed-origins (profile
 * specific); an empty list rejects all cross-origin requests. allowCredentials is enabled only for
 * explicit origins — it is never combined with a wildcard pattern.
 */
@Configuration
public class CorsConfig {

  @Bean
  public WebMvcConfigurer corsConfigurer(CorsProperties properties) {
    return new WebMvcConfigurer() {
      @Override
      public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = properties.getAllowedOrigins();
        var mapping =
            registry
                .addMapping("/api/**")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
        if (origins.stream().anyMatch("*"::equals)) {
          // Defensive: a wildcard origin can never be credentialed.
          mapping.allowedOriginPatterns(origins.toArray(new String[0])).allowCredentials(false);
        } else {
          mapping.allowedOrigins(origins.toArray(new String[0])).allowCredentials(true);
        }
      }
    };
  }
}
