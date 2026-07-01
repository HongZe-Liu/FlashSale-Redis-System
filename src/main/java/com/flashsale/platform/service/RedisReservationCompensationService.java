package com.flashsale.platform.service;

import com.flashsale.platform.observability.BusinessMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
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

    @Resource
    private BusinessMetrics businessMetrics;

    public boolean compensate(Long offerId, Long userId, Long orderId, String reason) {
        if (offerId == null || userId == null) {
            log.warn("Skipping Redis reservation compensation because arguments are incomplete, offerId={}, userId={}, orderId={}, reason={}",
                    offerId, userId, orderId, reason);
            businessMetrics.recordRedisCompensationFailure("invalid_arguments");
            return false;
        }

        Long result;
        try {
            result = stringRedisTemplate.execute(
                    RESERVATION_COMPENSATE_SCRIPT,
                    Collections.emptyList(),
                    offerId.toString(),
                    userId.toString()
            );
        } catch (RuntimeException e) {
            businessMetrics.recordRedisCompensationFailure("redis_exception");
            log.error("Redis reservation compensation failed during script execution, offerId={}, userId={}, orderId={}, reason={}",
                    offerId, userId, orderId, reason, e);
            throw e;
        }

        if (result == null) {
            log.error("Redis reservation compensation returned null, offerId={}, userId={}, orderId={}, reason={}",
                    offerId, userId, orderId, reason);
            businessMetrics.recordRedisCompensationFailure("script_result_null");
            return false;
        }
        if (result == 1L) {
            log.warn("Redis reservation compensation completed; stock and user reservation were restored, offerId={}, userId={}, orderId={}, reason={}",
                    offerId, userId, orderId, reason);
            businessMetrics.recordRedisCompensationSuccess(reason);
            return true;
        }
        if (result == 2L) {
            log.warn("Redis reservation was removed but stock key was missing, offerId={}, userId={}, orderId={}, reason={}",
                    offerId, userId, orderId, reason);
            businessMetrics.recordRedisCompensationFailure("stock_key_missing");
            return false;
        }

        log.info("Redis reservation compensation skipped because user reservation does not exist, offerId={}, userId={}, orderId={}, reason={}",
                offerId, userId, orderId, reason);
        businessMetrics.recordRedisCompensationNoop(reason);
        return false;
    }
}
