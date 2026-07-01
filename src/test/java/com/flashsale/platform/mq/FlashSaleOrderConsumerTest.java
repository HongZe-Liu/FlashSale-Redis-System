package com.flashsale.platform.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.platform.config.RabbitMqConfig;
import com.flashsale.platform.entity.Order;
import com.flashsale.platform.observability.BusinessMetrics;
import com.flashsale.platform.service.IOrderService;
import com.flashsale.platform.service.RedisReservationCompensationService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static com.flashsale.platform.utils.RedisConstants.FLASH_SALE_ORDER_KEY;
import static com.flashsale.platform.utils.RedisConstants.FLASH_SALE_STOCK_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlashSaleOrderConsumerTest {

    private static final long DELIVERY_TAG = 42L;
    private static final Long ORDER_ID = 9001L;
    private static final Long OFFER_ID = 101L;
    private static final Long USER_ID = 501L;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @Mock
    private IOrderService orderService;

    @Mock
    private RedisReservationCompensationService compensationService;

    @Mock
    private BusinessMetrics businessMetrics;

    @Mock
    private Channel channel;

    private FlashSaleOrderConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new FlashSaleOrderConsumer();
        ReflectionTestUtils.setField(consumer, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(consumer, "rabbitTemplate", rabbitTemplate);
        ReflectionTestUtils.setField(consumer, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(consumer, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(consumer, "orderService", orderService);
        ReflectionTestUtils.setField(consumer, "redisReservationCompensationService", compensationService);
        ReflectionTestUtils.setField(consumer, "businessMetrics", businessMetrics);
    }

    @Test
    void consume_whenRedisReservationMissing_acksWithoutCreatingOrder() throws Exception {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember(FLASH_SALE_ORDER_KEY + OFFER_ID, USER_ID.toString()))
                .thenReturn(false);

        consumer.consume(message(validBody()), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(orderService, never()).createOrder(any(Order.class));
        verify(redissonClient, never()).getLock(anyString());
        verify(businessMetrics).recordMqConsumeSuccess();
    }

    @Test
    void consume_whenOrderCreated_acksAndUnlocks() throws Exception {
        givenReservationExists();
        givenLockAcquired();
        when(orderService.createOrder(any(Order.class))).thenReturn(true);

        consumer.consume(message(validBody()), channel);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderService).createOrder(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getId()).isEqualTo(ORDER_ID);
        assertThat(orderCaptor.getValue().getOfferId()).isEqualTo(OFFER_ID);
        assertThat(orderCaptor.getValue().getUserId()).isEqualTo(USER_ID);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(lock).unlock();
        verify(businessMetrics).recordMqConsumeSuccess();
    }

    @Test
    void consume_whenOrderCreateReturnsFalse_compensatesRedisReservationAndAcks() throws Exception {
        givenReservationExists();
        givenLockAcquired();
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(orderService.createOrder(any(Order.class))).thenReturn(false);

        consumer.consume(message(validBody()), channel);

        verify(valueOperations).set(FLASH_SALE_STOCK_KEY + OFFER_ID, "0");
        verify(setOperations).remove(FLASH_SALE_ORDER_KEY + OFFER_ID, USER_ID.toString());
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(businessMetrics).recordMqConsumeSuccess();
    }

    @Test
    void consume_whenOrderServiceThrows_publishesRetryAndAcksOriginalMessage() throws Exception {
        givenReservationExists();
        givenLockAcquired();
        stubRabbitPublishConfirm(true);
        when(orderService.createOrder(any(Order.class))).thenThrow(new RuntimeException("database down"));

        consumer.consume(message(validBody()), channel);

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConfig.ORDER_RETRY_EXCHANGE),
                eq(RabbitMqConfig.ORDER_RETRY_ROUTING_KEY),
                any(FlashSaleOrderMessage.class),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(eq(DELIVERY_TAG), eq(false), eq(true));
        verify(businessMetrics).recordOrderCreateFailure("create_order_exception");
        verify(businessMetrics).recordMqConsumeFailure("create_order_exception");
        verify(businessMetrics).recordMqPublishSuccess("retry");
    }

    @Test
    void consume_whenRetryPublishFails_nacksOriginalMessageForRequeue() throws Exception {
        givenReservationExists();
        givenLockAcquired();
        stubRabbitPublishConfirm(false);
        when(orderService.createOrder(any(Order.class))).thenThrow(new RuntimeException("database down"));

        consumer.consume(message(validBody()), channel);

        verify(channel).basicNack(DELIVERY_TAG, false, true);
        verify(channel, never()).basicAck(DELIVERY_TAG, false);
        verify(businessMetrics).recordMqPublishFailure("retry", "confirm_nack");
    }

    @Test
    void consume_whenRetryCountReachesMax_publishesDeadLetterCompensatesAndAcks() throws Exception {
        givenReservationExists();
        givenLockAcquired();
        stubRabbitPublishConfirm(true);
        when(orderService.createOrder(any(Order.class))).thenThrow(new RuntimeException("database down"));
        Message message = message(validBody());
        message.getMessageProperties().setHeader("flashsale-retry-count", 2);

        consumer.consume(message, channel);

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConfig.ORDER_DEAD_EXCHANGE),
                eq(RabbitMqConfig.ORDER_DEAD_ROUTING_KEY),
                any(FlashSaleOrderDeadLetterMessage.class),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );
        verify(compensationService).compensate(
                OFFER_ID,
                USER_ID,
                ORDER_ID,
                "rabbitmq_dead_letter:create_order_exception"
        );
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(businessMetrics).recordMqDeadLetter("create_order_exception");
        verify(businessMetrics).recordMqPublishSuccess("dead_letter");
    }

    @Test
    void consume_whenMessageMissesRequiredFields_publishesDeadLetterAndAcks() throws Exception {
        stubRabbitPublishConfirm(true);

        consumer.consume(message("{\"orderId\":9001,\"offerId\":101}"), channel);

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConfig.ORDER_DEAD_EXCHANGE),
                eq(RabbitMqConfig.ORDER_DEAD_ROUTING_KEY),
                any(FlashSaleOrderDeadLetterMessage.class),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );
        verify(compensationService).compensate(
                OFFER_ID,
                null,
                ORDER_ID,
                "rabbitmq_dead_letter:missing_required_fields"
        );
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(businessMetrics).recordMqConsumeFailure("missing_required_fields");
        verify(businessMetrics).recordMqDeadLetter("missing_required_fields");
    }

    @Test
    void consume_whenMessageBodyIsInvalidJson_publishesDeadLetterWithoutCompensationAndAcks() throws Exception {
        stubRabbitPublishConfirm(true);

        consumer.consume(message("{not-json"), channel);

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMqConfig.ORDER_DEAD_EXCHANGE),
                eq(RabbitMqConfig.ORDER_DEAD_ROUTING_KEY),
                any(FlashSaleOrderDeadLetterMessage.class),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );
        verify(compensationService, never()).compensate(any(), any(), any(), anyString());
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(businessMetrics).recordMqConsumeFailure("message_convert_error");
        verify(businessMetrics).recordMqDeadLetter("message_convert_error");
    }

    @Test
    void consume_whenDuplicateKeyThrown_treatsAsIdempotentSuccess() throws Exception {
        givenReservationExists();
        givenLockAcquired();
        when(orderService.createOrder(any(Order.class))).thenThrow(new DuplicateKeyException("idx_user_offer"));

        consumer.consume(message(validBody()), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(businessMetrics).recordOrderCreateIdempotent();
        verify(businessMetrics).recordMqConsumeSuccess();
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    private void givenReservationExists() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember(FLASH_SALE_ORDER_KEY + OFFER_ID, USER_ID.toString()))
                .thenReturn(true);
    }

    private void givenLockAcquired() throws InterruptedException {
        when(redissonClient.getLock("lock:order:" + OFFER_ID + ":" + USER_ID)).thenReturn(lock);
        when(lock.tryLock(1, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    private void stubRabbitPublishConfirm(boolean ack) {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "nack"));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(),
                anyString(),
                any(),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );
    }

    private Message message(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    private String validBody() {
        return "{\"orderId\":9001,\"offerId\":101,\"userId\":501}";
    }
}
