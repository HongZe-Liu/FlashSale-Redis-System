package com.flashsale.payment.service;

import com.flashsale.payment.config.PaymentProperties;
import com.flashsale.payment.entity.Order;
import com.flashsale.payment.enums.OrderStatus;
import com.flashsale.payment.enums.PaymentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

import static com.flashsale.payment.utils.RedisConstants.FLASH_SALE_STOCK_KEY;

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
                    restoreRedisStock(order);
                }
            } catch (Exception e) {
                log.error("超时订单取消失败，orderId={}, userId={}, offerId={}",
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
            throw new IllegalStateException("回补MySQL库存失败");
        }

        log.warn("超时订单已取消并回补MySQL库存，orderId={}, userId={}, offerId={}",
                order.getId(), order.getUserId(), order.getOfferId());
        return true;
    }

    private void restoreRedisStock(Order order) {
        String stockKey = FLASH_SALE_STOCK_KEY + order.getOfferId();
        try {
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
                stringRedisTemplate.opsForValue().increment(stockKey);
                log.warn("超时订单已回补Redis库存，orderId={}, userId={}, offerId={}",
                        order.getId(), order.getUserId(), order.getOfferId());
            } else {
                log.warn("超时订单Redis库存Key不存在，跳过Redis库存回补，orderId={}, userId={}, offerId={}",
                        order.getId(), order.getUserId(), order.getOfferId());
            }
        } catch (Exception e) {
            log.error("超时订单Redis库存回补失败，需要人工核对，orderId={}, userId={}, offerId={}",
                    order.getId(), order.getUserId(), order.getOfferId(), e);
        }
    }
}
