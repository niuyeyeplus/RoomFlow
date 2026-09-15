package com.roomflow.domain.notification;

import com.roomflow.common.enums.NotificationType;

/**
 * RabbitMQ payload for asynchronous in-app notification creation. messageId is the idempotency key:
 * consumers deduplicate on it before persisting.
 */
public record NotificationMessage(
    String messageId,
    Long accountId,
    NotificationType type,
    String title,
    String content,
    Long meetingId) {}
