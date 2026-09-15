package com.roomflow.mq;

import com.roomflow.common.enums.NotificationType;
import com.roomflow.config.RabbitMqConfig;
import com.roomflow.domain.notification.NotificationMessage;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** Publishes notification events; assigns the messageId used by consumers for idempotency. */
@Component
public class NotificationProducer {

  private static final Logger log = LoggerFactory.getLogger(NotificationProducer.class);

  private final RabbitTemplate rabbitTemplate;

  public NotificationProducer(RabbitTemplate rabbitTemplate) {
    this.rabbitTemplate = rabbitTemplate;
  }

  public void send(NotificationMessage message) {
    String messageId =
        message.messageId() == null ? UUID.randomUUID().toString() : message.messageId();
    NotificationMessage payload =
        new NotificationMessage(
            messageId,
            message.accountId(),
            message.type(),
            message.title(),
            message.content(),
            message.meetingId());
    rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, RabbitMqConfig.ROUTING_KEY, payload);
    log.debug("Published notification message {} to account {}", messageId, message.accountId());
  }

  public void send(
      Long accountId, NotificationType type, String title, String content, Long meetingId) {
    send(new NotificationMessage(null, accountId, type, title, content, meetingId));
  }
}
