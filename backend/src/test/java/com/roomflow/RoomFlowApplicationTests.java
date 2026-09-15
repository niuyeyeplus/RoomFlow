package com.roomflow;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class RoomFlowApplicationTests {

  // Infrastructure is excluded in the test profile; these mocks satisfy bean wiring so the full
  // context (security chain, services, mappers via MyBatis-Plus) can load without middleware.
  @MockitoBean private DataSource dataSource;
  @MockitoBean private StringRedisTemplate stringRedisTemplate;
  @MockitoBean private RabbitTemplate rabbitTemplate;

  @Test
  void contextLoads() {
    // Verifies the Spring context starts successfully with test profile
  }
}
