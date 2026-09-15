package com.roomflow.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling + ShedLock wiring. The whole feature is gated on roomflow.scheduler.enabled (default
 * true) so Docker-free unit/MockMvc contexts can disable it — without a RedisConnectionFactory the
 * LockProvider bean cannot be built and the ShedLock scheduler proxy would fail at startup.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
@ConditionalOnProperty(
    name = "roomflow.scheduler.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ShedLockConfig {

  @Bean
  public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
    return new RedisLockProvider(connectionFactory, "roomflow");
  }
}
