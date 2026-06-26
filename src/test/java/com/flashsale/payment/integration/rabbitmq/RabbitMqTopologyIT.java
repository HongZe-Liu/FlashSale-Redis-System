package com.flashsale.payment.integration.rabbitmq;

import com.flashsale.payment.config.RabbitMqConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMqTopologyIT extends AbstractRabbitMqIT {

    @Test
    void topology_declaresMainRetryAndDeadLetterQueues() {
        assertQueueExists(RabbitMqConfig.ORDER_CREATE_QUEUE);
        assertQueueExists(RabbitMqConfig.ORDER_RETRY_QUEUE);
        assertQueueExists(RabbitMqConfig.ORDER_DEAD_QUEUE);
    }

    @Test
    void mainExchange_routesCreateMessagesToCreateQueue() {
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.ORDER_EXCHANGE,
                RabbitMqConfig.ORDER_CREATE_ROUTING_KEY,
                "create-message"
        );

        Message message = rabbitTemplate.receive(RabbitMqConfig.ORDER_CREATE_QUEUE, 5000);

        assertThat(message).isNotNull();
        assertThat(body(message)).isEqualTo("\"create-message\"");
    }

    @Test
    void retryQueue_deadLettersBackToCreateQueueAfterTtl() {
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.ORDER_RETRY_EXCHANGE,
                RabbitMqConfig.ORDER_RETRY_ROUTING_KEY,
                "retry-message"
        );

        Awaitility.await()
                .atMost(Duration.ofMillis(RabbitMqConfig.ORDER_RETRY_TTL_MS + 4000L))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    Message message = rabbitTemplate.receive(RabbitMqConfig.ORDER_CREATE_QUEUE, 100);
                    assertThat(message).isNotNull();
                    assertThat(body(message)).isEqualTo("\"retry-message\"");
                });
    }

    @Test
    void deadExchange_routesMessagesToDeadLetterQueue() {
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.ORDER_DEAD_EXCHANGE,
                RabbitMqConfig.ORDER_DEAD_ROUTING_KEY,
                "dead-message"
        );

        Message message = rabbitTemplate.receive(RabbitMqConfig.ORDER_DEAD_QUEUE, 5000);

        assertThat(message).isNotNull();
        assertThat(body(message)).isEqualTo("\"dead-message\"");
    }

    private void assertQueueExists(String queueName) {
        Properties queueProperties = rabbitAdmin.getQueueProperties(queueName);

        assertThat(queueProperties).isNotNull();
        assertThat(queueProperties.get(RabbitAdmin.QUEUE_NAME)).isEqualTo(queueName);
    }

    private String body(Message message) {
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }
}
