package com.roomflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RoomFlowApplication {
  public static void main(String[] args) {
    SpringApplication.run(RoomFlowApplication.class, args);
  }
}
