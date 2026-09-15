package com.roomflow.unit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roomflow.common.enums.NotificationType;
import com.roomflow.domain.notification.NotificationMessage;
import com.roomflow.mq.NotificationConsumer;
import com.roomflow.service.NotificationService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class NotificationConsumerTest {

  @Mock private StringRedisTemplate redis;
  @Mock private ValueOperations<String, String> ops;
  @Mock private NotificationService notificationService;

  private NotificationConsumer consumer;

  private static final NotificationMessage MSG =
      new NotificationMessage("msg-1", 1L, NotificationType.PARTICIPANT_JOINED, "t", "c", 10L);

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(ops);
    consumer = new NotificationConsumer(redis, notificationService);
  }

  @Test
  void processesFirstDelivery() {
    when(ops.setIfAbsent(eq("roomflow:mq:consumed:msg-1"), eq("1"), any(Duration.class)))
        .thenReturn(true);
    consumer.onMessage(MSG);
    verify(notificationService).saveFromMessage(MSG);
  }

  @Test
  void skipsDuplicateMessageId() {
    when(ops.setIfAbsent(eq("roomflow:mq:consumed:msg-1"), eq("1"), any(Duration.class)))
        .thenReturn(false);
    consumer.onMessage(MSG);
    verify(notificationService, never()).saveFromMessage(any());
  }

  @Test
  void releasesClaimOnFailureSoRetryReprocesses() {
    when(ops.setIfAbsent(eq("roomflow:mq:consumed:msg-1"), eq("1"), any(Duration.class)))
        .thenReturn(true);
    org.mockito.Mockito.doThrow(new RuntimeException("db down"))
        .when(notificationService)
        .saveFromMessage(MSG);
    assertThrows(RuntimeException.class, () -> consumer.onMessage(MSG));
    verify(redis).delete("roomflow:mq:consumed:msg-1");
  }
}
