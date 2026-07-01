package com.flashsale.platform.service;

import com.flashsale.platform.config.PaymentProperties;
import com.flashsale.platform.entity.Order;
import com.flashsale.platform.enums.OrderStatus;
import com.flashsale.platform.enums.PaymentStatus;
import com.flashsale.platform.observability.BusinessMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

import static com.flashsale.platform.utils.RedisConstants.FLASH_SALE_STOCK_KEY;

@Slf4j
@Service
public class OrderTimeoutService {

    @Resource
    private IOrderService orderService;

    @Resource
    private IPaymentOrderService paymentOrderService;

    @Resource
    private IFlashSaleOfferService flashSaleOfferService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private PaymentProperties paymentProperties;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private BusinessMetrics businessMetrics;

    @Scheduled(fixedDelayString = "${app.payment.timeout-scan-fixed-delay-ms:60000}")
    public void expirePendingOrders() {
        LocalDateTime now = LocalDateTime.now();
        List<Order> expiredOrders = orderService.query()
                .eq("status", OrderStatus.PENDING_PAYMENT.getCode())
                .isNotNull("expire_time")
                .lt("expire_time", now)
                .last("LIMIT " + paymentProperties.getTimeoutScanBatchSize())
                .list();
        if (expiredOrders.isEmpty()) {
            return;
        }

        for (Order order : expiredOrders) {
            try {
                Boolean expired = transactionTemplate.execute(status -> expireOrderInDatabase(order, now));
                if (Boolean.TRUE.equals(expired)) {
                    businessMetrics.recordOrderTimeoutExpired();
                    restoreRedisStock(order);
                }
            } catch (Exception e) {
                log.error("Failed to expire overdue order, orderId={}, userId={}, offerId={}",
                        order.getId(), order.getUserId(), order.getOfferId(), e);
            }
        }
    }

    private boolean expireOrderInDatabase(Order order, LocalDateTime now) {
        boolean orderExpired = orderService.update()
                .set("status", OrderStatus.EXPIRED.getCode())
                .eq("id", order.getId())
                .eq("status", OrderStatus.PENDING_PAYMENT.getCode())
                .lt("expire_time", now)
                .update();
        if (!orderExpired) {
            return false;
        }

        paymentOrderService.update()
                .set("status", PaymentStatus.EXPIRED.name())
                .eq("order_id", order.getId())
                .in("status", PaymentStatus.CREATED.name(), PaymentStatus.PENDING.name())
                .update();

        boolean stockRestored = flashSaleOfferService.update()
                .setSql("stock = stock + 1")
                .eq("offer_id", order.getOfferId())
                .update();
        if (!stockRestored) {
            throw new IllegalStateException("Failed to restore MySQL stock");
        }

        log.warn("Expired order was cancelled and MySQL stock restored, orderId={}, userId={}, offerId={}",
                order.getId(), order.getUserId(), order.getOfferId());
        return true;
    }

    private void restoreRedisStock(Order order) {
        String stockKey = FLASH_SALE_STOCK_KEY + order.getOfferId();
        try {
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
                stringRedisTemplate.opsForValue().increment(stockKey);
                log.warn("Redis stock restored for expired order, orderId={}, userId={}, offerId={}",
                        order.getId(), order.getUserId(), order.getOfferId());
            } else {
                log.warn("Redis stock key missing for expired order; restore skipped, orderId={}, userId={}, offerId={}",
                        order.getId(), order.getUserId(), order.getOfferId());
            }
        } catch (Exception e) {
            log.error("Failed to restore Redis stock for expired order; manual reconciliation required, orderId={}, userId={}, offerId={}",
                    order.getId(), order.getUserId(), order.getOfferId(), e);
        }
    }
}
