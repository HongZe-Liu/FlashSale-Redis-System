package com.flashsale.platform.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.platform.config.RabbitMqConfig;
import com.flashsale.platform.entity.Order;
import com.flashsale.platform.observability.BusinessMetrics;
import com.flashsale.platform.service.IOrderService;
import com.flashsale.platform.service.RedisReservationCompensationService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.flashsale.platform.utils.RedisConstants.FLASH_SALE_ORDER_KEY;
import static com.flashsale.platform.utils.RedisConstants.FLASH_SALE_STOCK_KEY;

@Slf4j
@Component
public class FlashSaleOrderConsumer {

    private static final String ORDER_LOCK_KEY = "lock:order:";
    private static final String RETRY_COUNT_HEADER = "flashsale-retry-count";
    private static final String ERROR_REASON_HEADER = "flashsale-error-reason";
    private static final int MAX_RETRY_COUNT = 3;
    private static final long PUBLISH_CONFIRM_TIMEOUT_MS = 5000L;
    private static final String DESTINATION_RETRY = "retry";
    private static final String DESTINATION_DEAD_LETTER = "dead_letter";

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private IOrderService orderService;

    @Resource
    private RedisReservationCompensationService redisReservationCompensationService;

    @Resource
    private BusinessMetrics businessMetrics;

    @RabbitListener(queues = RabbitMqConfig.ORDER_CREATE_QUEUE)
    public void consume(Message amqpMessage, Channel channel) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        FlashSaleOrderMessage message = null;
        try {
            message = objectMapper.readValue(amqpMessage.getBody(), FlashSaleOrderMessage.class);
            ConsumeDecision decision = handleOrderMessage(message);
            if (decision.status == ConsumeStatus.SUCCESS) {
                businessMetrics.recordMqConsumeSuccess();
                channel.basicAck(deliveryTag, false);
                return;
            }

            if (decision.status == ConsumeStatus.DEAD_LETTER) {
                businessMetrics.recordMqConsumeFailure(decision.reason);
                publishDeadLetterAndAck(message, amqpMessage, channel, deliveryTag, decision.reason,
                        retryCount(amqpMessage));
                return;
            }

            publishRetryOrDeadLetter(message, amqpMessage, channel, deliveryTag, decision.reason);
        } catch (Exception e) {
            if (message == null) {
                String reason = "message_convert_error";
                log.error("RabbitMQ flash sale order message cannot be deserialized and will be sent to DLQ, body={}",
                        originalMessageBody(amqpMessage), e);
                businessMetrics.recordMqConsumeFailure(reason);
                publishDeadLetterAndAck(null, amqpMessage, channel, deliveryTag,
                        reason, retryCount(amqpMessage));
                return;
            }
            log.error("Unexpected RabbitMQ flash sale order consume exception, message={}", message, e);
            publishRetryOrDeadLetter(message, amqpMessage, channel, deliveryTag,
                    "unexpected_consume_exception");
        }
    }

    private ConsumeDecision handleOrderMessage(FlashSaleOrderMessage message) {
        Long orderId = message.getOrderId();
        Long userId = message.getUserId();
        Long offerId = message.getOfferId();
        if (orderId == null || userId == null || offerId == null) {
            log.error("RabbitMQ flash sale order message is missing required fields, message={}", message);
            return ConsumeDecision.deadLetter("missing_required_fields");
        }

        Boolean reserved = stringRedisTemplate.opsForSet()
                .isMember(FLASH_SALE_ORDER_KEY + offerId, userId.toString());
        if (!Boolean.TRUE.equals(reserved)) {
            log.warn("Redis flash sale reservation does not exist; message is acknowledged as stale, userId={}, offerId={}, orderId={}",
                    userId, offerId, orderId);
            return ConsumeDecision.success();
        }

        RLock lock = redissonClient.getLock(ORDER_LOCK_KEY + offerId + ":" + userId);
        boolean locked;
        try {
            locked = lock.tryLock(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while acquiring user order lock, userId={}, offerId={}, orderId={}", userId, offerId, orderId);
            return ConsumeDecision.retry("order_lock_interrupted");
        }
        if (!locked) {
            log.warn("User order lock was not acquired; message will be retried, userId={}, offerId={}, orderId={}", userId, offerId, orderId);
            return ConsumeDecision.retry("order_lock_not_acquired");
        }

        try {
            Order order = new Order();
            order.setId(orderId);
            order.setOfferId(offerId);
            order.setUserId(userId);

            boolean created = orderService.createOrder(order);
            if (!created) {
                compensateDatabaseStockConflict(order);
            }
            return ConsumeDecision.success();
        } catch (DuplicateKeyException e) {
            log.warn("Order unique index conflict; treating as idempotent success, userId={}, offerId={}, orderId={}",
                    userId, offerId, orderId);
            businessMetrics.recordOrderCreateIdempotent();
            return ConsumeDecision.success();
        } catch (Exception e) {
            if (isDuplicateOrderException(e)) {
                log.warn("Order unique index conflict; treating as idempotent success, userId={}, offerId={}, orderId={}",
                        userId, offerId, orderId);
                businessMetrics.recordOrderCreateIdempotent();
                return ConsumeDecision.success();
            }
            log.error("Order persistence failed; message will be retried, userId={}, offerId={}, orderId={}",
                    userId, offerId, orderId, e);
            businessMetrics.recordOrderCreateFailure("create_order_exception");
            return ConsumeDecision.retry("create_order_exception");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void publishRetryOrDeadLetter(FlashSaleOrderMessage message, Message amqpMessage,
                                          Channel channel, long deliveryTag, String reason) throws IOException {
        businessMetrics.recordMqConsumeFailure(reason);
        int nextRetryCount = retryCount(amqpMessage) + 1;
        if (nextRetryCount >= MAX_RETRY_COUNT) {
            log.error("RabbitMQ flash sale order message exceeded retry limit and will be sent to DLQ, retryCount={}, reason={}, message={}",
                    nextRetryCount, reason, message);
            publishDeadLetterAndAck(message, amqpMessage, channel, deliveryTag, reason, nextRetryCount);
            return;
        }

        boolean published = publishWithConfirm(
                RabbitMqConfig.ORDER_RETRY_EXCHANGE,
                RabbitMqConfig.ORDER_RETRY_ROUTING_KEY,
                message,
                messagePostProcessor -> {
                    messagePostProcessor.getMessageProperties().setHeader(RETRY_COUNT_HEADER, nextRetryCount);
                    messagePostProcessor.getMessageProperties().setHeader(ERROR_REASON_HEADER, reason);
                    return messagePostProcessor;
                },
                "retry-" + message.getOrderId() + "-" + nextRetryCount,
                DESTINATION_RETRY
        );

        if (published) {
            log.warn("RabbitMQ flash sale order message was published to retry queue, retryCount={}, reason={}, message={}",
                    nextRetryCount, reason, message);
            channel.basicAck(deliveryTag, false);
        } else {
            log.error("Failed to publish RabbitMQ flash sale order message to retry queue; original message will be requeued, retryCount={}, reason={}, message={}",
                    nextRetryCount, reason, message);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private void publishDeadLetterAndAck(FlashSaleOrderMessage message, Message amqpMessage,
                                         Channel channel, long deliveryTag, String reason,
                                         int retryCount) throws IOException {
        FlashSaleOrderDeadLetterMessage deadLetterMessage = new FlashSaleOrderDeadLetterMessage(
                message == null ? null : message.getOrderId(),
                message == null ? null : message.getOfferId(),
                message == null ? null : message.getUserId(),
                reason,
                retryCount,
                originalMessageBody(amqpMessage),
                LocalDateTime.now()
        );

        boolean published = publishWithConfirm(
                RabbitMqConfig.ORDER_DEAD_EXCHANGE,
                RabbitMqConfig.ORDER_DEAD_ROUTING_KEY,
                deadLetterMessage,
                messagePostProcessor -> {
                    messagePostProcessor.getMessageProperties().setHeader(RETRY_COUNT_HEADER, retryCount);
                    messagePostProcessor.getMessageProperties().setHeader(ERROR_REASON_HEADER, reason);
                    return messagePostProcessor;
                },
                "dead-" + UUID.randomUUID(),
                DESTINATION_DEAD_LETTER
        );

        if (published) {
            businessMetrics.recordMqDeadLetter(reason);
            if (message != null) {
                redisReservationCompensationService.compensate(
                        message.getOfferId(),
                        message.getUserId(),
                        message.getOrderId(),
                        "rabbitmq_dead_letter:" + reason
                );
            }
            channel.basicAck(deliveryTag, false);
            return;
        }

        log.error("Failed to publish RabbitMQ flash sale order message to DLQ; original message will be requeued, reason={}, retryCount={}, message={}",
                reason, retryCount, message);
        channel.basicNack(deliveryTag, false, true);
    }

    private boolean publishWithConfirm(String exchange, String routingKey, Object payload,
                                       org.springframework.amqp.core.MessagePostProcessor postProcessor,
                                       String correlationId, String destination) {
        CorrelationData correlationData = new CorrelationData(correlationId);
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, payload, postProcessor, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(PUBLISH_CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                log.error("RabbitMQ forwarded message was not confirmed, exchange={}, routingKey={}, correlationId={}, reason={}, payload={}",
                        exchange, routingKey, correlationId, confirm.getReason(), payload);
                businessMetrics.recordMqPublishFailure(destination, "confirm_nack");
                return false;
            }
            if (correlationData.getReturned() != null) {
                log.error("RabbitMQ forwarded message was returned as unroutable, exchange={}, routingKey={}, correlationId={}, payload={}",
                        exchange, routingKey, correlationId, payload);
                businessMetrics.recordMqPublishFailure(destination, "returned");
                return false;
            }
            businessMetrics.recordMqPublishSuccess(destination);
            return true;
        } catch (TimeoutException e) {
            log.error("RabbitMQ forwarded message confirm timed out, exchange={}, routingKey={}, correlationId={}, payload={}",
                    exchange, routingKey, correlationId, payload, e);
            businessMetrics.recordMqPublishFailure(destination, "confirm_timeout");
            return false;
        } catch (AmqpException e) {
            log.error("RabbitMQ forwarded message failed with AMQP exception, exchange={}, routingKey={}, correlationId={}, payload={}",
                    exchange, routingKey, correlationId, payload, e);
            businessMetrics.recordMqPublishFailure(destination, "amqp_exception");
            return false;
        } catch (Exception e) {
            log.error("RabbitMQ forwarded message failed unexpectedly, exchange={}, routingKey={}, correlationId={}, payload={}",
                    exchange, routingKey, correlationId, payload, e);
            businessMetrics.recordMqPublishFailure(destination, "unexpected_exception");
            return false;
        }
    }

    private int retryCount(Message message) {
        Map<String, Object> headers = message.getMessageProperties().getHeaders();
        Object retryCount = headers.get(RETRY_COUNT_HEADER);
        if (retryCount instanceof Number) {
            return ((Number) retryCount).intValue();
        }
        if (retryCount instanceof String) {
            try {
                return Integer.parseInt((String) retryCount);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private void compensateDatabaseStockConflict(Order order) {
        String offerId = order.getOfferId().toString();
        String userId = order.getUserId().toString();
        stringRedisTemplate.opsForValue().set(FLASH_SALE_STOCK_KEY + offerId, "0");
        stringRedisTemplate.opsForSet().remove(FLASH_SALE_ORDER_KEY + offerId, userId);
        log.warn("Database stock deduction failed; Redis stock and user reservation were conservatively corrected, userId={}, offerId={}, orderId={}",
                userId, offerId, order.getId());
    }

    private String originalMessageBody(Message message) {
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }

    private boolean isDuplicateOrderException(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof DuplicateKeyException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("idx_user_offer")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private enum ConsumeStatus {
        SUCCESS,
        RETRY,
        DEAD_LETTER
    }

    private static class ConsumeDecision {
        private final ConsumeStatus status;
        private final String reason;

        private ConsumeDecision(ConsumeStatus status, String reason) {
            this.status = status;
            this.reason = reason;
        }

        private static ConsumeDecision success() {
            return new ConsumeDecision(ConsumeStatus.SUCCESS, null);
        }

        private static ConsumeDecision retry(String reason) {
            return new ConsumeDecision(ConsumeStatus.RETRY, reason);
        }

        private static ConsumeDecision deadLetter(String reason) {
            return new ConsumeDecision(ConsumeStatus.DEAD_LETTER, reason);
        }
    }
}
