package com.roomflow.mq;

import com.roomflow.config.RabbitMqConfig;
import com.roomflow.domain.notification.NotificationMessage;
import com.roomflow.service.NotificationService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotent consumer: the messageId is claimed in Redis before persisting, so redelivered or
 * republished duplicates are acknowledged without creating a second notification. On failure the
 * claim is released and the exception propagates so retry/DLQ policies apply.
 *
 * <p>Known trade-off: the claim is written BEFORE the DB insert, so a crash in the tiny
 * claim-then-persist window drops the message on redelivery (an at-most-once sliver inside the
 * otherwise at-least-once semantics). Closing it fully would need a DB unique constraint on
 * message_id + transactional dedup — tracked as a backlog optimization, accepted for this slice.
 */
@Component
public class NotificationConsumer {

  private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);
  private static final String CONSUMED_PREFIX = "roomflow:mq:consumed:";
  private static final Duration CONSUMED_TTL = Duration.ofDays(7);

  private final StringRedisTemplate redis;
  private final NotificationService notificationService;

  public NotificationConsumer(StringRedisTemplate redis, NotificationService notificationService) {
    this.redis = redis;
    this.notificationService = notificationService;
  }

  @RabbitListener(queues = RabbitMqConfig.QUEUE)
  public void onMessage(NotificationMessage message) {
    String key = CONSUMED_PREFIX + message.messageId();
    Boolean claimed = redis.opsForValue().setIfAbsent(key, "1", CONSUMED_TTL);
    if (!Boolean.TRUE.equals(claimed)) {
      log.info("Duplicate notification message {} ignored", message.messageId());
      return;
    }
    try {
      notificationService.saveFromMessage(message);
    } catch (RuntimeException e) {
      // Release the claim so a retried delivery can process it again.
      redis.delete(key);
      throw e;
    }
  }
}
