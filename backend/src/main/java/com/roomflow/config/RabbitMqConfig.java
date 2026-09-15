package com.roomflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Notification topology: main queue dead-letters to a DLQ after the configured retries are
 * exhausted (default-requeue-rejected=false + spring.rabbitmq.listener.simple.retry).
 */
@Configuration
public class RabbitMqConfig {

  public static final String EXCHANGE = "roomflow.notification.exchange";
  public static final String QUEUE = "roomflow.notification.queue";
  public static final String ROUTING_KEY = "roomflow.notification";
  public static final String DLX = "roomflow.notification.dlx";
  public static final String DLQ = "roomflow.notification.dlq";
  public static final String DLQ_ROUTING_KEY = "roomflow.notification.dead";

  @Bean
  public DirectExchange notificationExchange() {
    return new DirectExchange(EXCHANGE, true, false);
  }

  @Bean
  public Queue notificationQueue() {
    return QueueBuilder.durable(QUEUE)
        .withArgument("x-dead-letter-exchange", DLX)
        .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
        .build();
  }

  @Bean
  public Binding notificationBinding(Queue notificationQueue, DirectExchange notificationExchange) {
    return BindingBuilder.bind(notificationQueue).to(notificationExchange).with(ROUTING_KEY);
  }

  @Bean
  public DirectExchange notificationDlx() {
    return new DirectExchange(DLX, true, false);
  }

  @Bean
  public Queue notificationDlq() {
    return QueueBuilder.durable(DLQ).build();
  }

  @Bean
  public Binding notificationDlqBinding(Queue notificationDlq, DirectExchange notificationDlx) {
    return BindingBuilder.bind(notificationDlq).to(notificationDlx).with(DLQ_ROUTING_KEY);
  }

  /**
   * JSON message conversion shared by the RabbitTemplate and the listener container factory.
   * Deserialization is restricted to the com.roomflow type hierarchy (allowed-list patterns)
   * instead of the wildcard default.
   */
  @Bean
  public MessageConverter notificationMessageConverter(ObjectMapper objectMapper) {
    return new Jackson2JsonMessageConverter(objectMapper, "com.roomflow.**");
  }
}
