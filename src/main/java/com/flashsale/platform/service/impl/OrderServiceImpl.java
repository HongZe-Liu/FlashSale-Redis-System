package com.flashsale.platform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashsale.platform.config.PaymentProperties;
import com.flashsale.platform.dto.Result;
import com.flashsale.platform.entity.Offer;
import com.flashsale.platform.entity.Order;
import com.flashsale.platform.enums.OrderStatus;
import com.flashsale.platform.enums.PaymentProviderType;
import com.flashsale.platform.mapper.OfferMapper;
import com.flashsale.platform.mapper.OrderMapper;
import com.flashsale.platform.mq.FlashSaleOrderMessage;
import com.flashsale.platform.mq.FlashSaleOrderProducer;
import com.flashsale.platform.observability.BusinessMetrics;
import com.flashsale.platform.service.IFlashSaleOfferService;
import com.flashsale.platform.service.IOrderService;
import com.flashsale.platform.service.RedisReservationCompensationService;
import com.flashsale.platform.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;

@Service
@Slf4j
public class OrderServiceImpl
        extends ServiceImpl<OrderMapper, Order>
        implements IOrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private IFlashSaleOfferService flashSaleOfferService;

    @Resource
    private OfferMapper offerMapper;

    @Resource
    private PaymentProperties paymentProperties;

    @Resource
    private FlashSaleOrderProducer flashSaleOrderProducer;

    @Resource
    private RedisReservationCompensationService redisReservationCompensationService;

    @Resource
    private BusinessMetrics businessMetrics;

    private static final DefaultRedisScript<Long> FLASH_SALE_SCRIPT;

    static {
        FLASH_SALE_SCRIPT = new DefaultRedisScript<>();
        FLASH_SALE_SCRIPT.setResultType(Long.class);
        FLASH_SALE_SCRIPT.setLocation(new ClassPathResource("flash-sale.lua"));
    }

    @Override
    public Result placeFlashSaleOrder(Long offerId, Long userId) {
        businessMetrics.recordFlashSaleRequestTotal();
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(
                FLASH_SALE_SCRIPT,
                Collections.emptyList(),
                offerId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        if (result == null) {
            businessMetrics.recordFlashSaleRequestFailure("lua_result_null");
            return Result.fail("Flash sale request failed; please retry later");
        }
        if (result.intValue() != 0) {
            businessMetrics.recordFlashSaleRequestFailure(flashSaleFailReason(result.intValue()));
            return Result.fail(flashSaleFailMessage(result.intValue()));
        }

        FlashSaleOrderMessage message = new FlashSaleOrderMessage(orderId, offerId, userId, LocalDateTime.now());
        boolean published = flashSaleOrderProducer.publish(message);
        if (!published) {
            businessMetrics.recordFlashSaleRequestFailure("mq_publish_failed");
            redisReservationCompensationService.compensate(
                    offerId,
                    userId,
                    orderId,
                    "rabbitmq_publish_failed"
            );
            return Result.fail("Flash sale request failed; please retry later");
        }
        businessMetrics.recordFlashSaleRequestSuccess();
        return Result.ok(orderId);
    }

    @Override
    @Transactional
    public boolean createOrder(Order order) {
        Long userId = order.getUserId();
        Long offerId = order.getOfferId();

        int count = query()
                .eq("offer_id", offerId)
                .eq("user_id", userId)
                .count();
        if (count > 0) {
            log.warn("Order already exists; treating as idempotent success, userId={}, offerId={}", userId, offerId);
            businessMetrics.recordOrderCreateIdempotent();
            return true;
        }

        Offer offer = offerMapper.selectById(offerId);
        if (offer == null || offer.getPriceAmount() == null || offer.getPriceAmount() <= 0) {
            log.warn("Failed to snapshot order amount because offer is invalid, userId={}, offerId={}", userId, offerId);
            businessMetrics.recordOrderCreateFailure("invalid_offer");
            return false;
        }

        boolean stockUpdated = flashSaleOfferService.update()
                .setSql("stock = stock -1")
                .eq("offer_id", offerId)
                .gt("stock", 0)
                .update();

        if (!stockUpdated) {
            log.warn("Database stock deduction failed; Redis reservation will be compensated, userId={}, offerId={}", userId, offerId);
            businessMetrics.recordOrderCreateFailure("db_stock_not_enough");
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        order.setPayType(PaymentProviderType.MOCK.getCode());
        order.setStatus(OrderStatus.PENDING_PAYMENT.getCode());
        order.setPayAmount(offer.getPriceAmount());
        order.setCurrency("EUR");
        order.setCreateTime(now);
        order.setExpireTime(now.plusMinutes(paymentProperties.getOrderExpireMinutes()));

        boolean saved = save(order);
        if (!saved) {
            throw new IllegalStateException("Failed to save flash sale order");
        }
        businessMetrics.recordOrderCreateSuccess();
        return true;
    }

    private String flashSaleFailReason(int code) {
        switch (code) {
            case 1:
                return "stock_not_enough";
            case 2:
                return "duplicate_order";
            case 3:
                return "not_started";
            case 4:
                return "ended";
            case 5:
                return "not_initialized";
            default:
                return "unknown";
        }
    }

    private String flashSaleFailMessage(int code) {
        switch (code) {
            case 1:
                return "Insufficient stock";
            case 2:
                return "Duplicate order is not allowed";
            case 3:
                return "Flash sale has not started";
            case 4:
                return "Flash sale has ended";
            case 5:
                return "Flash sale is not initialized";
            default:
                return "Flash sale request failed; please retry later";
        }
    }
}
