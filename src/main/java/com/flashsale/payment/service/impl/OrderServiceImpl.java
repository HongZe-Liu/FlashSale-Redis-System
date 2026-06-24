package com.flashsale.payment.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashsale.payment.dto.Result;
import com.flashsale.payment.entity.Order;
import com.flashsale.payment.mapper.OrderMapper;
import com.flashsale.payment.mq.FlashSaleOrderMessage;
import com.flashsale.payment.mq.FlashSaleOrderProducer;
import com.flashsale.payment.service.IFlashSaleOfferService;
import com.flashsale.payment.service.IOrderService;
import com.flashsale.payment.service.RedisReservationCompensationService;
import com.flashsale.payment.utils.RedisIdWorker;
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
    private FlashSaleOrderProducer flashSaleOrderProducer;

    @Resource
    private RedisReservationCompensationService redisReservationCompensationService;

    private static final DefaultRedisScript<Long> FLASH_SALE_SCRIPT;

    static {
        FLASH_SALE_SCRIPT = new DefaultRedisScript<>();
        FLASH_SALE_SCRIPT.setResultType(Long.class);
        FLASH_SALE_SCRIPT.setLocation(new ClassPathResource("flash-sale.lua"));
    }

    @Override
    public Result placeFlashSaleOrder(Long offerId, Long userId) {
        long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(
                FLASH_SALE_SCRIPT,
                Collections.emptyList(),
                offerId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        if (result == null) {
            return Result.fail("抢购失败，请稍后重试");
        }
        if (result.intValue() != 0) {
            return Result.fail(flashSaleFailMessage(result.intValue()));
        }

        FlashSaleOrderMessage message = new FlashSaleOrderMessage(orderId, offerId, userId, LocalDateTime.now());
        boolean published = flashSaleOrderProducer.publish(message);
        if (!published) {
            redisReservationCompensationService.compensate(
                    offerId,
                    userId,
                    orderId,
                    "rabbitmq_publish_failed"
            );
            return Result.fail("抢购失败，请稍后重试");
        }
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
            log.warn("订单已存在，按幂等成功处理，userId={}, offerId={}", userId, offerId);
            return true;
        }

        boolean stockUpdated = flashSaleOfferService.update()
                .setSql("stock = stock -1")
                .eq("offer_id", offerId)
                .gt("stock", 0)
                .update();

        if (!stockUpdated) {
            log.warn("数据库库存扣减失败，将由消费端触发Redis预扣补偿，userId={}, offerId={}", userId, offerId);
            return false;
        }

        boolean saved = save(order);
        if (!saved) {
            throw new IllegalStateException("保存秒杀订单失败");
        }
        return true;
    }

    private String flashSaleFailMessage(int code) {
        switch (code) {
            case 1:
                return "库存不足";
            case 2:
                return "不能重复下单";
            case 3:
                return "秒杀活动尚未开始";
            case 4:
                return "秒杀活动已经结束";
            case 5:
                return "秒杀活动未初始化";
            default:
                return "抢购失败，请稍后重试";
        }
    }
}
