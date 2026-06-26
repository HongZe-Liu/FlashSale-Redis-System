package com.flashsale.payment.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.flashsale.payment.dto.MockPaymentWebhookRequest;
import com.flashsale.payment.dto.Result;
import com.flashsale.payment.entity.Order;
import com.flashsale.payment.entity.PaymentOrder;
import com.flashsale.payment.entity.PaymentWebhookEvent;
import com.flashsale.payment.enums.OrderStatus;
import com.flashsale.payment.enums.PaymentProviderType;
import com.flashsale.payment.enums.PaymentStatus;
import com.flashsale.payment.enums.WebhookEventStatus;
import com.flashsale.payment.observability.BusinessMetrics;
import com.flashsale.payment.service.IOrderService;
import com.flashsale.payment.service.IPaymentOrderService;
import com.flashsale.payment.service.IPaymentWebhookEventService;
import com.flashsale.payment.utils.RedisIdWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookServiceImplTest {

    private static final Long ORDER_ID = 1001L;
    private static final Long PAYMENT_ORDER_ID = 9001L;
    private static final Long USER_ID = 501L;

    @Mock
    private IPaymentWebhookEventService paymentWebhookEventService;

    @Mock
    private IPaymentOrderService paymentOrderService;

    @Mock
    private IOrderService orderService;

    @Mock
    private RedisIdWorker redisIdWorker;

    @Mock
    private BusinessMetrics businessMetrics;

    private PaymentWebhookServiceImpl webhookService;

    @BeforeEach
    void setUp() {
        webhookService = new PaymentWebhookServiceImpl();
        ReflectionTestUtils.setField(webhookService, "paymentWebhookEventService", paymentWebhookEventService);
        ReflectionTestUtils.setField(webhookService, "paymentOrderService", paymentOrderService);
        ReflectionTestUtils.setField(webhookService, "orderService", orderService);
        ReflectionTestUtils.setField(webhookService, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(webhookService, "businessMetrics", businessMetrics);
    }

    @Test
    void handleMockPaymentSucceeded_whenEventIdMissing_returnsFailureWithoutSavingEvent() {
        MockPaymentWebhookRequest request = validRequest();
        request.setEventId(" ");

        Result result = webhookService.handleMockPaymentSucceeded(request, "{}");

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("eventId不能为空");
        verify(paymentWebhookEventService, never()).save(any());
        verify(businessMetrics).recordPaymentWebhookFailure(PaymentProviderType.MOCK.name(), "event_id_missing");
    }

    @Test
    void handleMockPaymentSucceeded_whenDuplicateProcessedEvent_returnsOk() {
        when(redisIdWorker.nextId("webhook_event")).thenReturn(7001L);
        when(paymentWebhookEventService.save(any(PaymentWebhookEvent.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        webhookEventQueryReturning(webhookEvent(WebhookEventStatus.PROCESSED, null));

        Result result = webhookService.handleMockPaymentSucceeded(validRequest(), "{\"eventId\":\"evt_1\"}");

        assertThat(result.getSuccess()).isTrue();
        verify(paymentOrderService, never()).query();
        verify(businessMetrics).recordPaymentWebhookDuplicate(PaymentProviderType.MOCK.name(), "processed");
    }

    @Test
    void handleMockPaymentSucceeded_whenAmountMismatches_marksEventFailed() {
        when(redisIdWorker.nextId("webhook_event")).thenReturn(7001L);
        when(paymentWebhookEventService.save(any(PaymentWebhookEvent.class))).thenReturn(true);
        PaymentOrder paymentOrder = pendingPaymentOrder();
        paymentOrder.setAmount(999L);
        paymentOrderQueryReturning(paymentOrder);
        when(paymentWebhookEventService.updateById(any(PaymentWebhookEvent.class))).thenReturn(true);

        Result result = webhookService.handleMockPaymentSucceeded(validRequest(), "{\"eventId\":\"evt_1\"}");

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("webhook金额不匹配");

        ArgumentCaptor<PaymentWebhookEvent> captor = ArgumentCaptor.forClass(PaymentWebhookEvent.class);
        verify(paymentWebhookEventService).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(WebhookEventStatus.FAILED.name());
        assertThat(captor.getValue().getErrorMessage()).isEqualTo("webhook金额不匹配");
        verify(orderService, never()).update();
        verify(businessMetrics).recordPaymentWebhookFailure(PaymentProviderType.MOCK.name(), "amount_mismatch");
    }

    @Test
    void handleMockPaymentSucceeded_whenValid_updatesOrderPaymentAndEvent() {
        when(redisIdWorker.nextId("webhook_event")).thenReturn(7001L);
        when(paymentWebhookEventService.save(any(PaymentWebhookEvent.class))).thenReturn(true);
        when(paymentWebhookEventService.updateById(any(PaymentWebhookEvent.class))).thenReturn(true);
        paymentOrderQueryReturning(pendingPaymentOrder());
        when(orderService.getById(ORDER_ID)).thenReturn(pendingOrder());
        UpdateChainWrapper<Order> orderUpdate = orderUpdateReturning(true);
        UpdateChainWrapper<PaymentOrder> paymentUpdate = paymentUpdateReturning(true);

        Result result = webhookService.handleMockPaymentSucceeded(validRequest(), "{\"eventId\":\"evt_1\"}");

        assertThat(result.getSuccess()).isTrue();
        verify(orderUpdate).set("status", OrderStatus.PAID.getCode());
        verify(orderUpdate).eq("id", ORDER_ID);
        verify(orderUpdate).eq("status", OrderStatus.PENDING_PAYMENT.getCode());
        verify(paymentUpdate).set("status", PaymentStatus.PAID.name());
        verify(paymentUpdate).eq("id", PAYMENT_ORDER_ID);

        ArgumentCaptor<PaymentWebhookEvent> captor = ArgumentCaptor.forClass(PaymentWebhookEvent.class);
        verify(paymentWebhookEventService).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(WebhookEventStatus.PROCESSED.name());
        assertThat(captor.getValue().getProcessedAt()).isNotNull();
        verify(businessMetrics).recordPaymentWebhookSuccess(PaymentProviderType.MOCK.name());
    }

    @Test
    void handleMockPaymentSucceeded_whenPaymentStatusUpdateFails_throwsAfterOrderUpdate() {
        when(redisIdWorker.nextId("webhook_event")).thenReturn(7001L);
        when(paymentWebhookEventService.save(any(PaymentWebhookEvent.class))).thenReturn(true);
        paymentOrderQueryReturning(pendingPaymentOrder());
        when(orderService.getById(ORDER_ID)).thenReturn(pendingOrder());
        orderUpdateReturning(true);
        paymentUpdateReturning(false);

        assertThatThrownBy(() -> webhookService.handleMockPaymentSucceeded(validRequest(), "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("支付单状态更新失败");

        verify(businessMetrics).recordPaymentWebhookFailure(
                PaymentProviderType.MOCK.name(),
                "payment_status_update_failed"
        );
    }

    private MockPaymentWebhookRequest validRequest() {
        MockPaymentWebhookRequest request = new MockPaymentWebhookRequest();
        request.setEventId("evt_1");
        request.setEventType("payment.succeeded");
        request.setProviderPaymentId("mock_pay_9001");
        request.setOrderId(ORDER_ID);
        request.setAmount(1299L);
        request.setCurrency("EUR");
        return request;
    }

    private PaymentOrder pendingPaymentOrder() {
        PaymentOrder paymentOrder = new PaymentOrder();
        paymentOrder.setId(PAYMENT_ORDER_ID);
        paymentOrder.setOrderId(ORDER_ID);
        paymentOrder.setUserId(USER_ID);
        paymentOrder.setProvider(PaymentProviderType.MOCK.name());
        paymentOrder.setProviderPaymentId("mock_pay_9001");
        paymentOrder.setAmount(1299L);
        paymentOrder.setCurrency("EUR");
        paymentOrder.setStatus(PaymentStatus.PENDING.name());
        return paymentOrder;
    }

    private Order pendingOrder() {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setUserId(USER_ID);
        order.setStatus(OrderStatus.PENDING_PAYMENT.getCode());
        order.setPayAmount(1299L);
        order.setCurrency("EUR");
        return order;
    }

    private PaymentWebhookEvent webhookEvent(WebhookEventStatus status, String errorMessage) {
        PaymentWebhookEvent event = new PaymentWebhookEvent();
        event.setId(7001L);
        event.setProvider(PaymentProviderType.MOCK.name());
        event.setEventId("evt_1");
        event.setStatus(status.name());
        event.setErrorMessage(errorMessage);
        return event;
    }

    @SuppressWarnings("unchecked")
    private QueryChainWrapper<PaymentWebhookEvent> webhookEventQueryReturning(PaymentWebhookEvent event) {
        QueryChainWrapper<PaymentWebhookEvent> query = mock(QueryChainWrapper.class, Answers.RETURNS_SELF);
        when(paymentWebhookEventService.query()).thenReturn(query);
        doReturn(event).when(query).one();
        return query;
    }

    @SuppressWarnings("unchecked")
    private QueryChainWrapper<PaymentOrder> paymentOrderQueryReturning(PaymentOrder paymentOrder) {
        QueryChainWrapper<PaymentOrder> query = mock(QueryChainWrapper.class, Answers.RETURNS_SELF);
        when(paymentOrderService.query()).thenReturn(query);
        doReturn(paymentOrder).when(query).one();
        return query;
    }

    @SuppressWarnings("unchecked")
    private UpdateChainWrapper<Order> orderUpdateReturning(boolean updated) {
        UpdateChainWrapper<Order> update = mock(UpdateChainWrapper.class, Answers.RETURNS_SELF);
        when(orderService.update()).thenReturn(update);
        when(update.update()).thenReturn(updated);
        return update;
    }

    @SuppressWarnings("unchecked")
    private UpdateChainWrapper<PaymentOrder> paymentUpdateReturning(boolean updated) {
        UpdateChainWrapper<PaymentOrder> update = mock(UpdateChainWrapper.class, Answers.RETURNS_SELF);
        when(paymentOrderService.update()).thenReturn(update);
        when(update.update()).thenReturn(updated);
        return update;
    }
}
