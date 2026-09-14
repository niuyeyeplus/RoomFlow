package com.roomflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class RoomFlowApplicationTests {

  @Test
  void contextLoads() {
    // Verifies the Spring context starts successfully with test profile
  }
}
