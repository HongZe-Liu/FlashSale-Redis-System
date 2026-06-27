package com.flashsale.platform.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.flashsale.platform.config.PaymentProperties;
import com.flashsale.platform.dto.CreatePaymentRequest;
import com.flashsale.platform.dto.CreatePaymentResponse;
import com.flashsale.platform.dto.PaymentStatusResponse;
import com.flashsale.platform.dto.Result;
import com.flashsale.platform.entity.Order;
import com.flashsale.platform.entity.PaymentOrder;
import com.flashsale.platform.enums.OrderStatus;
import com.flashsale.platform.enums.PaymentProviderType;
import com.flashsale.platform.enums.PaymentStatus;
import com.flashsale.platform.observability.BusinessMetrics;
import com.flashsale.platform.provider.PaymentProvider;
import com.flashsale.platform.provider.PaymentProviderResult;
import com.flashsale.platform.service.IOrderService;
import com.flashsale.platform.service.IPaymentOrderService;
import com.flashsale.platform.utils.RedisIdWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    private static final Long ORDER_ID = 1001L;
    private static final Long USER_ID = 501L;

    @Mock
    private IOrderService orderService;

    @Mock
    private IPaymentOrderService paymentOrderService;

    @Mock
    private RedisIdWorker redisIdWorker;

    @Mock
    private PaymentProvider paymentProvider;

    @Mock
    private BusinessMetrics businessMetrics;

    private PaymentProperties paymentProperties;
    private PaymentServiceImpl paymentService;

    @BeforeEach
    void setUp() {
        paymentProperties = new PaymentProperties();
        paymentProperties.setProvider(PaymentProviderType.MOCK);

        when(paymentProvider.providerType()).thenReturn(PaymentProviderType.MOCK);

        paymentService = new PaymentServiceImpl();
        ReflectionTestUtils.setField(paymentService, "orderService", orderService);
        ReflectionTestUtils.setField(paymentService, "paymentOrderService", paymentOrderService);
        ReflectionTestUtils.setField(paymentService, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(paymentService, "paymentProperties", paymentProperties);
        ReflectionTestUtils.setField(paymentService, "paymentProviders", Collections.singletonList(paymentProvider));
        ReflectionTestUtils.setField(paymentService, "businessMetrics", businessMetrics);
        paymentService.initProviders();
    }

    @Test
    void createPayment_whenArgumentsMissing_returnsFailureWithoutLoadingOrder() {
        Result result = paymentService.createPayment(null, USER_ID, request("MOCK"));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("Order arguments are incomplete");
        verify(orderService, never()).getById(any());
        verify(businessMetrics).recordPaymentCreateFailure("unknown", "invalid_arguments");
    }

    @Test
    void createPayment_whenOrderExpired_returnsFailure() {
        Order order = validOrder();
        order.setExpireTime(LocalDateTime.now().minusSeconds(1));
        when(orderService.getById(ORDER_ID)).thenReturn(order);

        Result result = paymentService.createPayment(ORDER_ID, USER_ID, request("MOCK"));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("Order has expired; please wait for cancellation");
        verify(paymentOrderService, never()).save(any());
        verify(businessMetrics).recordPaymentCreateFailure("unknown", "order_expired");
    }

    @Test
    void createPayment_whenOrderNotFound_returnsFailure() {
        when(orderService.getById(ORDER_ID)).thenReturn(null);

        Result result = paymentService.createPayment(ORDER_ID, USER_ID, request("MOCK"));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("Order does not exist");
        verify(paymentOrderService, never()).query();
        verify(businessMetrics).recordPaymentCreateFailure("unknown", "order_not_found");
    }

    @Test
    void createPayment_whenUserDoesNotOwnOrder_returnsFailure() {
        Order order = validOrder();
        order.setUserId(999L);
        when(orderService.getById(ORDER_ID)).thenReturn(order);

        Result result = paymentService.createPayment(ORDER_ID, USER_ID, request("MOCK"));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("Not allowed to pay this order");
        verify(paymentOrderService, never()).query();
        verify(businessMetrics).recordPaymentCreateFailure("unknown", "forbidden_order_owner");
    }

    @Test
    void createPayment_whenOrderStatusIsNotPendingPayment_returnsFailure() {
        Order order = validOrder();
        order.setStatus(OrderStatus.PAID.getCode());
        when(orderService.getById(ORDER_ID)).thenReturn(order);

        Result result = paymentService.createPayment(ORDER_ID, USER_ID, request("MOCK"));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("Order status does not allow payment");
        verify(paymentOrderService, never()).query();
        verify(businessMetrics).recordPaymentCreateFailure("unknown", "invalid_order_status");
    }

    @Test
    void createPayment_whenOrderAmountIsInvalid_returnsFailure() {
        Order order = validOrder();
        order.setPayAmount(0L);
        when(orderService.getById(ORDER_ID)).thenReturn(order);

        Result result = paymentService.createPayment(ORDER_ID, USER_ID, request("MOCK"));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("Invalid order amount");
        verify(paymentOrderService, never()).query();
        verify(businessMetrics).recordPaymentCreateFailure("unknown", "invalid_amount");
    }

    @Test
    void createPayment_whenOrderCurrencyIsInvalid_returnsFailure() {
        Order order = validOrder();
        order.setCurrency(" ");
        when(orderService.getById(ORDER_ID)).thenReturn(order);

        Result result = paymentService.createPayment(ORDER_ID, USER_ID, request("MOCK"));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("Invalid order currency");
        verify(paymentOrderService, never()).query();
        verify(businessMetrics).recordPaymentCreateFailure("unknown", "invalid_currency");
    }

    @Test
    void createPayment_whenExistingPendingPayment_reusesExistingPayment() {
        when(orderService.getById(ORDER_ID)).thenReturn(validOrder());
        PaymentOrder existing = paymentOrder(PaymentStatus.PENDING);
        existing.setProviderPaymentId("mock_pay_existing");
        existing.setCheckoutUrl("mock://checkout/existing");
        paymentOrderQueryReturning(existing);

        Result result = paymentService.createPayment(ORDER_ID, USER_ID, request("MOCK"));

        assertThat(result.getSuccess()).isTrue();
        CreatePaymentResponse response = (CreatePaymentResponse) result.getData();
        assertThat(response.getPaymentOrderId()).isEqualTo(existing.getId());
        assertThat(response.getProviderPaymentId()).isEqualTo("mock_pay_existing");
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.PENDING.name());
        verify(paymentOrderService, never()).save(any());
        verify(paymentProvider, never()).createPayment(any());
        verify(businessMetrics).recordPaymentCreateReused(PaymentProviderType.MOCK.name());
    }

    @Test
    void createPayment_whenExistingPaidPayment_reusesExistingPayment() {
        when(orderService.getById(ORDER_ID)).thenReturn(validOrder());
        PaymentOrder existing = paymentOrder(PaymentStatus.PAID);
        existing.setProviderPaymentId("mock_pay_paid");
        existing.setCheckoutUrl("mock://checkout/paid");
        paymentOrderQueryReturning(existing);

        Result result = paymentService.createPayment(ORDER_ID, USER_ID, request("MOCK"));

        assertThat(result.getSuccess()).isTrue();
        CreatePaymentResponse response = (CreatePaymentResponse) result.getData();
        assertThat(response.getPaymentOrderId()).isEqualTo(existing.getId());
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.PAID.name());
        verify(paymentOrderService, never()).save(any());
        verify(paymentProvider, never()).createPayment(any());
        verify(businessMetrics).recordPaymentCreateReused(PaymentProviderType.MOCK.name());
    }

    @Test
    void createPayment_whenExistingCreatedPayment_returnsCreatingFailure() {
        when(orderService.getById(ORDER_ID)).thenReturn(validOrder());
        paymentOrderQueryReturning(paymentOrder(PaymentStatus.CREATED));

        Result result = paymentService.createPayment(ORDER_ID, USER_ID, request("MOCK"));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("Payment order is being created; please retry later");
        verify(paymentOrderService, never()).save(any());
        verify(paymentProvider, never()).createPayment(any());
        verify(businessMetrics).recordPaymentCreateFailure(PaymentProviderType.MOCK.name(), "payment_creating");
    }

    @Test
    void createPayment_whenExistingFailedPayment_returnsStatusNotAllowed() {
        when(orderService.getById(ORDER_ID)).thenReturn(validOrder());
        paymentOrderQueryReturning(paymentOrder(PaymentStatus.FAILED));

        Result result = paymentService.createPayment(ORDER_ID, USER_ID, request("MOCK"));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("Current payment status does not allow retry");
        verify(paymentOrderService, never()).save(any());
        verify(paymentProvider, never()).createPayment(any());
        verify(businessMetrics).recordPaymentCreateFailure(PaymentProviderType.MOCK.name(), "payment_status_not_allowed");
    }

    @Test
    void createPayment_whenUnsupportedProviderRequested_returnsFailure() {
        when(orderService.getById(ORDER_ID)).thenReturn(validOrder());
        paymentOrderQueryReturning(null);

        Result result = paymentService.createPayment(ORDER_ID, USER_ID, request("UNKNOWN"));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("Unsupported payment provider");
        verify(paymentOrderService, never()).save(any());
        verify(businessMetrics).recordPaymentCreateFailure("unknown", "unsupported_provider");
    }

    @Test
    void createPayment_whenProviderIsConfiguredButUnavailable_returnsFailure() {
        when(orderService.getById(ORDER_ID)).thenReturn(validOrder());
        paymentOrderQueryReturning(null);

        Result result = paymentService.createPayment(ORDER_ID, USER_ID, request("STRIPE"));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("Payment provider is temporarily unavailable");
        verify(paymentOrderService, never()).save(any());
        verify(businessMetrics).recordPaymentCreateFailure(PaymentProviderType.STRIPE.name(), "provider_unavailable");
    }

    @Test
    void createPayment_whenPaymentOrderSaveReturnsFalse_returnsFailure() {
        when(orderService.getById(ORDER_ID)).thenReturn(validOrder());
        paymentOrderQueryReturning(null);
        when(redisIdWorker.nextId("payment")).thenReturn(9001L);
        when(paymentOrderService.save(any(PaymentOrder.class))).thenReturn(false);

        Result result = paymentService.createPayment(ORDER_ID, USER_ID, request("MOCK"));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("Failed to create payment order");
        verify(paymentProvider, never()).createPayment(any());
        verify(businessMetrics).recordPaymentCreateFailure(PaymentProviderType.MOCK.name(), "save_failed");
    }

    @Test
    void createPayment_whenProviderThrows_marksPaymentFailed() {
        when(orderService.getById(ORDER_ID)).thenReturn(validOrder());
        paymentOrderQueryReturning(null);
        when(redisIdWorker.nextId("payment")).thenReturn(9001L);
        when(paymentOrderService.save(any(PaymentOrder.class))).thenReturn(true);
        when(paymentProvider.createPayment(any(PaymentOrder.class)))
                .thenThrow(new RuntimeException("provider down"));
        when(paymentOrderService.updateById(any(PaymentOrder.class))).thenReturn(true);

        Result result = paymentService.createPayment(ORDER_ID, USER_ID, request("MOCK"));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("Failed to create payment; please retry later");

        ArgumentCaptor<PaymentOrder> captor = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(paymentOrderService).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED.name());
        assertThat(captor.getValue().getFailureReason()).isEqualTo(RuntimeException.class.getSimpleName());
        verify(businessMetrics).recordPaymentCreateFailure(PaymentProviderType.MOCK.name(), "provider_exception");
    }

    @Test
    void createPayment_whenProviderSucceeds_returnsCheckoutInfo() {
        when(orderService.getById(ORDER_ID)).thenReturn(validOrder());
        paymentOrderQueryReturning(null);
        when(redisIdWorker.nextId("payment")).thenReturn(9001L);
        when(paymentOrderService.save(any(PaymentOrder.class))).thenReturn(true);
        when(paymentProvider.createPayment(any(PaymentOrder.class))).thenAnswer(invocation -> {
            PaymentOrder paymentOrder = invocation.getArgument(0);
            assertThat(paymentOrder.getId()).isEqualTo(9001L);
            assertThat(paymentOrder.getOrderId()).isEqualTo(ORDER_ID);
            assertThat(paymentOrder.getUserId()).isEqualTo(USER_ID);
            assertThat(paymentOrder.getProvider()).isEqualTo(PaymentProviderType.MOCK.name());
            assertThat(paymentOrder.getStatus()).isEqualTo(PaymentStatus.CREATED.name());
            return new PaymentProviderResult(
                    "mock_pay_9001",
                    "mock://checkout/9001",
                    PaymentStatus.PENDING
            );
        });
        when(paymentOrderService.updateById(any(PaymentOrder.class))).thenReturn(true);

        Result result = paymentService.createPayment(ORDER_ID, USER_ID, request("MOCK"));

        assertThat(result.getSuccess()).isTrue();
        CreatePaymentResponse response = (CreatePaymentResponse) result.getData();
        assertThat(response.getPaymentOrderId()).isEqualTo(9001L);
        assertThat(response.getProviderPaymentId()).isEqualTo("mock_pay_9001");
        assertThat(response.getCheckoutUrl()).isEqualTo("mock://checkout/9001");
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.PENDING.name());
        verify(businessMetrics).recordPaymentCreateSuccess(PaymentProviderType.MOCK.name());
    }

    @Test
    void queryPaymentStatus_whenOrderAndPaymentExist_returnsSnapshot() {
        when(orderService.getById(ORDER_ID)).thenReturn(validOrder());
        PaymentOrder paymentOrder = paymentOrder(PaymentStatus.PENDING);
        paymentOrderQueryReturning(paymentOrder);

        Result result = paymentService.queryPaymentStatus(ORDER_ID, USER_ID);

        assertThat(result.getSuccess()).isTrue();
        PaymentStatusResponse response = (PaymentStatusResponse) result.getData();
        assertThat(response.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(response.getOrderStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT.name());
        assertThat(response.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING.name());
        assertThat(response.getProvider()).isEqualTo(PaymentProviderType.MOCK.name());
        assertThat(response.getAmount()).isEqualTo(1299L);
        assertThat(response.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void queryPaymentStatus_whenUserDoesNotOwnOrder_returnsFailure() {
        Order order = validOrder();
        order.setUserId(999L);
        when(orderService.getById(ORDER_ID)).thenReturn(order);

        Result result = paymentService.queryPaymentStatus(ORDER_ID, USER_ID);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("Not allowed to view this order payment status");
        verify(paymentOrderService, never()).query();
    }

    private CreatePaymentRequest request(String provider) {
        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setProvider(provider);
        return request;
    }

    private Order validOrder() {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setUserId(USER_ID);
        order.setStatus(OrderStatus.PENDING_PAYMENT.getCode());
        order.setPayAmount(1299L);
        order.setCurrency("EUR");
        order.setExpireTime(LocalDateTime.now().plusMinutes(10));
        return order;
    }

    private PaymentOrder paymentOrder(PaymentStatus status) {
        PaymentOrder paymentOrder = new PaymentOrder();
        paymentOrder.setId(8001L);
        paymentOrder.setOrderId(ORDER_ID);
        paymentOrder.setUserId(USER_ID);
        paymentOrder.setProvider(PaymentProviderType.MOCK.name());
        paymentOrder.setAmount(1299L);
        paymentOrder.setCurrency("EUR");
        paymentOrder.setStatus(status.name());
        return paymentOrder;
    }

    @SuppressWarnings("unchecked")
    private QueryChainWrapper<PaymentOrder> paymentOrderQueryReturning(PaymentOrder paymentOrder) {
        QueryChainWrapper<PaymentOrder> query = mock(QueryChainWrapper.class, Answers.RETURNS_SELF);
        when(paymentOrderService.query()).thenReturn(query);
        doReturn(paymentOrder).when(query).one();
        return query;
    }
}
