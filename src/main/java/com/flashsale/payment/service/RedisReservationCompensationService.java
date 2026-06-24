package com.flashsale.payment.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;

@Slf4j
@Service
public class RedisReservationCompensationService {

    private static final DefaultRedisScript<Long> RESERVATION_COMPENSATE_SCRIPT;

    static {
        RESERVATION_COMPENSATE_SCRIPT = new DefaultRedisScript<>();
        RESERVATION_COMPENSATE_SCRIPT.setResultType(Long.class);
        RESERVATION_COMPENSATE_SCRIPT.setLocation(new ClassPathResource("flash-sale-reservation-compensate.lua"));
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public boolean compensate(Long offerId, Long userId, Long orderId, String reason) {
        if (offerId == null || userId == null) {
            log.warn("跳过Redis秒杀资格补偿，参数不完整，offerId={}, userId={}, orderId={}, reason={}",
                    offerId, userId, orderId, reason);
            return false;
        }

        Long result = stringRedisTemplate.execute(
                RESERVATION_COMPENSATE_SCRIPT,
                Collections.emptyList(),
                offerId.toString(),
                userId.toString()
        );

        if (result == null) {
            log.error("Redis秒杀资格补偿执行结果为空，offerId={}, userId={}, orderId={}, reason={}",
                    offerId, userId, orderId, reason);
            return false;
        }
        if (result == 1L) {
            log.warn("Redis秒杀资格补偿成功，已回滚库存和用户资格，offerId={}, userId={}, orderId={}, reason={}",
                    offerId, userId, orderId, reason);
            return true;
        }
        if (result == 2L) {
            log.warn("Redis秒杀资格已移除，但库存Key不存在，未回补库存，offerId={}, userId={}, orderId={}, reason={}",
                    offerId, userId, orderId, reason);
            return false;
        }

        log.info("Redis秒杀资格无需补偿，用户资格不存在，offerId={}, userId={}, orderId={}, reason={}",
                offerId, userId, orderId, reason);
        return false;
    }
}
