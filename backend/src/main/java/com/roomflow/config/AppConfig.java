package com.roomflow.config;

import com.roomflow.common.util.BeijingTime;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

  /** Business clock pinned to Asia/Shanghai; inject Clock everywhere instead of calling now(). */
  @Bean
  public Clock clock() {
    return Clock.system(BeijingTime.ZONE);
  }
}
