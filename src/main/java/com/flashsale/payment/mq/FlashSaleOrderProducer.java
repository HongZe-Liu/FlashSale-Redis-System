package com.flashsale.payment.mq;

import com.flashsale.payment.config.RabbitMqConfig;
import com.flashsale.payment.observability.BusinessMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class FlashSaleOrderProducer {

    private static final long PUBLISH_CONFIRM_TIMEOUT_MS = 5000L;
    private static final String DESTINATION_ORDER_CREATE = "order_create";

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private BusinessMetrics businessMetrics;

    public boolean publish(FlashSaleOrderMessage message) {
        String correlationId = buildCorrelationId(message);
        CorrelationData correlationData = new CorrelationData(correlationId);
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.ORDER_EXCHANGE,
                    RabbitMqConfig.ORDER_CREATE_ROUTING_KEY,
                    message,
                    correlationData
            );

            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(PUBLISH_CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                log.error("RabbitMQ订单消息投递未确认，correlationId={}, reason={}, message={}",
                        correlationId, confirm.getReason(), message);
                businessMetrics.recordMqPublishFailure(DESTINATION_ORDER_CREATE, "confirm_nack");
                return false;
            }

            Message returnedMessage = correlationData.getReturnedMessage();
            if (returnedMessage != null) {
                log.error("RabbitMQ订单消息不可路由，correlationId={}, exchange={}, routingKey={}, message={}",
                        correlationId,
                        RabbitMqConfig.ORDER_EXCHANGE,
                        RabbitMqConfig.ORDER_CREATE_ROUTING_KEY,
                        message);
                businessMetrics.recordMqPublishFailure(DESTINATION_ORDER_CREATE, "returned");
                return false;
            }

            log.debug("RabbitMQ订单消息投递成功，correlationId={}, message={}", correlationId, message);
            businessMetrics.recordMqPublishSuccess(DESTINATION_ORDER_CREATE);
            return true;
        } catch (TimeoutException e) {
            log.error("RabbitMQ订单消息投递确认超时，correlationId={}, timeoutMs={}, message={}",
                    correlationId, PUBLISH_CONFIRM_TIMEOUT_MS, message, e);
            businessMetrics.recordMqPublishFailure(DESTINATION_ORDER_CREATE, "confirm_timeout");
            return false;
        } catch (AmqpException e) {
            log.error("RabbitMQ订单消息发送异常，correlationId={}, message={}", correlationId, message, e);
            businessMetrics.recordMqPublishFailure(DESTINATION_ORDER_CREATE, "amqp_exception");
            return false;
        } catch (Exception e) {
            log.error("RabbitMQ订单消息投递出现未预期异常，correlationId={}, message={}",
                    correlationId, message, e);
            businessMetrics.recordMqPublishFailure(DESTINATION_ORDER_CREATE, "unexpected_exception");
            return false;
        }
    }

    private String buildCorrelationId(FlashSaleOrderMessage message) {
        if (message != null && message.getOrderId() != null) {
            return "flashsale-order-" + message.getOrderId();
        }
        return "flashsale-order-" + UUID.randomUUID();
    }
}
