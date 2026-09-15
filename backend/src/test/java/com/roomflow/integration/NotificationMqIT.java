package com.roomflow.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.roomflow.common.enums.NotificationType;
import com.roomflow.common.enums.Role;
import com.roomflow.config.RabbitMqConfig;
import com.roomflow.domain.account.Account;
import com.roomflow.domain.notification.Notification;
import com.roomflow.domain.notification.NotificationMessage;
import com.roomflow.mapper.AccountMapper;
import com.roomflow.mapper.NotificationMapper;
import com.roomflow.mq.NotificationProducer;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real RabbitMQ round-trip: produce -> consume -> DB row; duplicate messageId consumed once; a
 * message that always fails lands on the DLQ after retries.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class NotificationMqIT extends AbstractContainersIT {

  @Autowired private NotificationProducer producer;
  @Autowired private NotificationMapper notificationMapper;
  @Autowired private AccountMapper accountMapper;
  @Autowired private RabbitTemplate rabbitTemplate;

  private static void await(Duration timeout, Runnable assertion) throws InterruptedException {
    Instant deadline = Instant.now().plus(timeout);
    AssertionError last = null;
    while (Instant.now().isBefore(deadline)) {
      try {
        assertion.run();
        return;
      } catch (AssertionError e) {
        last = e;
        Thread.sleep(300);
      }
    }
    throw last == null ? new AssertionError("timed out") : last;
  }

  private Long accountId(String username) {
    Account a = new Account();
    a.setUsername(username);
    a.setPasswordHash("$2a$10$0123456789012345678901234567890123456789012345678901");
    a.setRole(Role.USER);
    a.setStatus(1);
    accountMapper.insert(a);
    return a.getId();
  }

  private long notificationsFor(Long accountId) {
    return notificationMapper.selectCount(
        new LambdaQueryWrapper<Notification>().eq(Notification::getAccountId, accountId));
  }

  @Test
  void produceConsumeAndDeduplicate() throws Exception {
    Long uid = accountId("mq_user_a");
    String messageId = "it-dedup-" + System.nanoTime();
    NotificationMessage msg =
        new NotificationMessage(
            messageId, uid, NotificationType.PARTICIPANT_JOINED, "新参会者", "content", null);

    producer.send(msg);
    producer.send(msg); // same messageId: consumer must process exactly once

    await(Duration.ofSeconds(30), () -> assertEquals(1L, notificationsFor(uid)));
    // Give a potential duplicate delivery time to surface, then assert still one row.
    Thread.sleep(3000);
    assertEquals(1L, notificationsFor(uid));
  }

  @Test
  void poisonMessageLandsInDlq() throws Exception {
    // accountId references a non-existent account -> FK violation in consumer -> retries -> DLQ.
    String messageId = "it-poison-" + System.nanoTime();
    NotificationMessage msg =
        new NotificationMessage(
            messageId, 999999999L, NotificationType.PARTICIPANT_LEFT, "退出", "content", null);
    producer.send(msg);

    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    Message dead = null;
    while (Instant.now().isBefore(deadline)) {
      dead = rabbitTemplate.receive(RabbitMqConfig.DLQ, 3000);
      if (dead != null) {
        break;
      }
    }
    assertNotNull(dead, "poison message must reach the DLQ after retries");
    assertTrue(new String(dead.getBody()).contains(messageId));
  }
}
