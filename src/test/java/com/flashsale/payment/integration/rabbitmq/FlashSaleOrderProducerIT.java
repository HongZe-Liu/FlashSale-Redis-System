package com.flashsale.payment.integration.rabbitmq;

import com.flashsale.payment.config.RabbitMqConfig;
import com.flashsale.payment.mq.FlashSaleOrderMessage;
import com.flashsale.payment.mq.FlashSaleOrderProducer;
import com.flashsale.payment.observability.BusinessMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FlashSaleOrderProducerIT extends AbstractRabbitMqIT {

    @Test
    void publish_whenBrokerConfirmsAndMessageIsRoutable_returnsTrueAndEnqueuesMessage() {
        BusinessMetrics businessMetrics = mock(BusinessMetrics.class);
        FlashSaleOrderProducer producer = new FlashSaleOrderProducer();
        ReflectionTestUtils.setField(producer, "rabbitTemplate", rabbitTemplate);
        ReflectionTestUtils.setField(producer, "businessMetrics", businessMetrics);

        boolean published = producer.publish(new FlashSaleOrderMessage(
                9001L,
                101L,
                501L,
                LocalDateTime.of(2026, 6, 26, 12, 0)
        ));

        assertThat(published).isTrue();
        verify(businessMetrics).recordMqPublishSuccess("order_create");

        Message message = rabbitTemplate.receive(RabbitMqConfig.ORDER_CREATE_QUEUE, 5000);
        assertThat(message).isNotNull();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"orderId\":9001");
        assertThat(body).contains("\"offerId\":101");
        assertThat(body).contains("\"userId\":501");
    }
}
