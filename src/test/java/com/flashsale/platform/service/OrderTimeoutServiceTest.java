package com.flashsale.platform.service;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.flashsale.platform.config.PaymentProperties;
import com.flashsale.platform.entity.FlashSaleOffer;
import com.flashsale.platform.entity.Order;
import com.flashsale.platform.entity.PaymentOrder;
import com.flashsale.platform.enums.OrderStatus;
import com.flashsale.platform.enums.PaymentStatus;
import com.flashsale.platform.observability.BusinessMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Collections;

import static com.flashsale.platform.utils.RedisConstants.FLASH_SALE_STOCK_KEY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderTimeoutServiceTest {

    private static final Long ORDER_ID = 9001L;
    private static final Long OFFER_ID = 101L;
    private static final Long USER_ID = 501L;

    @Mock
    private IOrderService orderService;

    @Mock
    private IPaymentOrderService paymentOrderService;

    @Mock
    private IFlashSaleOfferService flashSaleOfferService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private BusinessMetrics businessMetrics;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private TransactionStatus transactionStatus;

    private OrderTimeoutService orderTimeoutService;
    private PaymentProperties paymentProperties;

    @BeforeEach
    void setUp() {
        paymentProperties = new PaymentProperties();
        paymentProperties.setTimeoutScanBatchSize(50);

        orderTimeoutService = new OrderTimeoutService();
        ReflectionTestUtils.setField(orderTimeoutService, "orderService", orderService);
        ReflectionTestUtils.setField(orderTimeoutService, "paymentOrderService", paymentOrderService);
        ReflectionTestUtils.setField(orderTimeoutService, "flashSaleOfferService", flashSaleOfferService);
        ReflectionTestUtils.setField(orderTimeoutService, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(orderTimeoutService, "paymentProperties", paymentProperties);
        ReflectionTestUtils.setField(orderTimeoutService, "transactionTemplate", transactionTemplate);
        ReflectionTestUtils.setField(orderTimeoutService, "businessMetrics", businessMetrics);
    }

    @Test
    void expirePendingOrders_whenNoExpiredOrders_doesNothing() {
        expiredOrderQueryReturning();

        orderTimeoutService.expirePendingOrders();

        verify(orderService).query();
        verify(transactionTemplate, never()).execute(any());
        verifyNoInteractions(paymentOrderService, flashSaleOfferService, stringRedisTemplate, businessMetrics);
    }

    @Test
    void expirePendingOrders_whenOrderExpires_restoresMysqlAndRedisStock() {
        Order order = expiredOrder();
        expiredOrderQueryReturning(order);
        executeTransactionCallbacks();
        UpdateChainWrapper<Order> orderUpdate = orderUpdateReturning(true);
        UpdateChainWrapper<PaymentOrder> paymentOrderUpdate = paymentOrderUpdateReturning(true);
        UpdateChainWrapper<FlashSaleOffer> offerUpdate = offerUpdateReturning(true);
        when(stringRedisTemplate.hasKey(FLASH_SALE_STOCK_KEY + OFFER_ID)).thenReturn(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        orderTimeoutService.expirePendingOrders();

        verify(orderUpdate).set("status", OrderStatus.EXPIRED.getCode());
        verify(orderUpdate).eq("id", ORDER_ID);
        verify(orderUpdate).eq("status", OrderStatus.PENDING_PAYMENT.getCode());
        verify(orderUpdate).lt(eq("expire_time"), any(LocalDateTime.class));
        verify(orderUpdate).update();

        verify(paymentOrderUpdate).set("status", PaymentStatus.EXPIRED.name());
        verify(paymentOrderUpdate).eq("order_id", ORDER_ID);
        verify(paymentOrderUpdate).update();

        verify(offerUpdate).setSql("stock = stock + 1");
        verify(offerUpdate).eq("offer_id", OFFER_ID);
        verify(offerUpdate).update();

        verify(valueOperations).increment(FLASH_SALE_STOCK_KEY + OFFER_ID);
        verify(businessMetrics).recordOrderTimeoutExpired();
    }

    @Test
    void expirePendingOrders_whenOrderWasAlreadyChanged_skipsStockRestoration() {
        expiredOrderQueryReturning(expiredOrder());
        executeTransactionCallbacks();
        orderUpdateReturning(false);

        orderTimeoutService.expirePendingOrders();

        verify(paymentOrderService, never()).update();
        verify(flashSaleOfferService, never()).update();
        verifyNoInteractions(stringRedisTemplate, businessMetrics);
    }

    @Test
    void expirePendingOrders_whenMysqlStockRestoreFails_doesNotRestoreRedisStock() {
        expiredOrderQueryReturning(expiredOrder());
        executeTransactionCallbacks();
        orderUpdateReturning(true);
        paymentOrderUpdateReturning(true);
        offerUpdateReturning(false);

        orderTimeoutService.expirePendingOrders();

        verify(stringRedisTemplate, never()).hasKey(any());
        verify(businessMetrics, never()).recordOrderTimeoutExpired();
    }

    @Test
    void expirePendingOrders_whenRedisStockKeyMissing_recordsTimeoutWithoutIncrementingRedis() {
        expiredOrderQueryReturning(expiredOrder());
        executeTransactionCallbacks();
        orderUpdateReturning(true);
        paymentOrderUpdateReturning(true);
        offerUpdateReturning(true);
        when(stringRedisTemplate.hasKey(FLASH_SALE_STOCK_KEY + OFFER_ID)).thenReturn(false);

        orderTimeoutService.expirePendingOrders();

        verify(stringRedisTemplate, never()).opsForValue();
        verify(businessMetrics).recordOrderTimeoutExpired();
    }

    @SuppressWarnings("unchecked")
    private QueryChainWrapper<Order> expiredOrderQueryReturning(Order... orders) {
        QueryChainWrapper<Order> query = mock(QueryChainWrapper.class, Answers.RETURNS_SELF);
        when(orderService.query()).thenReturn(query);
        doReturn(orders.length == 0 ? Collections.emptyList() : Collections.singletonList(orders[0]))
                .when(query)
                .list();
        return query;
    }

    private void executeTransactionCallbacks() {
        doAnswer(invocation -> {
            TransactionCallback<Boolean> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        }).when(transactionTemplate).execute(any());
    }

    @SuppressWarnings("unchecked")
    private UpdateChainWrapper<Order> orderUpdateReturning(boolean updated) {
        UpdateChainWrapper<Order> update = mock(UpdateChainWrapper.class, Answers.RETURNS_SELF);
        when(orderService.update()).thenReturn(update);
        doReturn(updated).when(update).update();
        return update;
    }

    @SuppressWarnings("unchecked")
    private UpdateChainWrapper<PaymentOrder> paymentOrderUpdateReturning(boolean updated) {
        UpdateChainWrapper<PaymentOrder> update = mock(UpdateChainWrapper.class, Answers.RETURNS_SELF);
        when(paymentOrderService.update()).thenReturn(update);
        doReturn(updated).when(update).update();
        return update;
    }

    @SuppressWarnings("unchecked")
    private UpdateChainWrapper<FlashSaleOffer> offerUpdateReturning(boolean updated) {
        UpdateChainWrapper<FlashSaleOffer> update = mock(UpdateChainWrapper.class, Answers.RETURNS_SELF);
        when(flashSaleOfferService.update()).thenReturn(update);
        doReturn(updated).when(update).update();
        return update;
    }

    private Order expiredOrder() {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOfferId(OFFER_ID);
        order.setUserId(USER_ID);
        order.setStatus(OrderStatus.PENDING_PAYMENT.getCode());
        order.setExpireTime(LocalDateTime.now().minusMinutes(1));
        return order;
    }
}
