package com.flashsale.platform.integration.redis;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;

import static com.flashsale.platform.utils.RedisConstants.FLASH_SALE_ORDER_KEY;
import static com.flashsale.platform.utils.RedisConstants.FLASH_SALE_STOCK_KEY;
import static org.assertj.core.api.Assertions.assertThat;

class RedisReservationCompensationIT extends AbstractRedisIT {

    private static final DefaultRedisScript<Long> COMPENSATION_SCRIPT = new DefaultRedisScript<>();

    static {
        COMPENSATION_SCRIPT.setResultType(Long.class);
        COMPENSATION_SCRIPT.setLocation(new ClassPathResource("flash-sale-reservation-compensate.lua"));
    }

    @Test
    void compensate_whenReservationExistsAndStockExists_removesUserAndIncrementsStock() {
        redisTemplate.opsForValue().set(stockKey("101"), "1");
        redisTemplate.opsForSet().add(orderKey("101"), "501");

        Long firstResult = execute("101", "501");
        Long secondResult = execute("101", "501");

        assertThat(firstResult).isEqualTo(1L);
        assertThat(secondResult).isEqualTo(0L);
        assertThat(redisTemplate.opsForValue().get(stockKey("101"))).isEqualTo("2");
        assertThat(redisTemplate.opsForSet().isMember(orderKey("101"), "501")).isFalse();
    }

    @Test
    void compensate_whenReservationExistsButStockMissing_removesUserAndReturnsCode2() {
        redisTemplate.opsForSet().add(orderKey("101"), "501");

        Long result = execute("101", "501");

        assertThat(result).isEqualTo(2L);
        assertThat(redisTemplate.hasKey(stockKey("101"))).isFalse();
        assertThat(redisTemplate.opsForSet().isMember(orderKey("101"), "501")).isFalse();
    }

    @Test
    void compensate_whenReservationMissing_returnsCode0WithoutChangingStock() {
        redisTemplate.opsForValue().set(stockKey("101"), "1");

        Long result = execute("101", "501");

        assertThat(result).isEqualTo(0L);
        assertThat(redisTemplate.opsForValue().get(stockKey("101"))).isEqualTo("1");
    }

    private Long execute(String offerId, String userId) {
        return redisTemplate.execute(
                COMPENSATION_SCRIPT,
                Collections.emptyList(),
                offerId,
                userId
        );
    }

    private String stockKey(String offerId) {
        return FLASH_SALE_STOCK_KEY + offerId;
    }

    private String orderKey(String offerId) {
        return FLASH_SALE_ORDER_KEY + offerId;
    }
}
