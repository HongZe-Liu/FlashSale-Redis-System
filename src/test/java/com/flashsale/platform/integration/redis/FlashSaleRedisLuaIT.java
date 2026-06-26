package com.flashsale.platform.integration.redis;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static com.flashsale.platform.utils.RedisConstants.FLASH_SALE_OFFER_KEY;
import static com.flashsale.platform.utils.RedisConstants.FLASH_SALE_ORDER_KEY;
import static com.flashsale.platform.utils.RedisConstants.FLASH_SALE_STOCK_KEY;
import static org.assertj.core.api.Assertions.assertThat;

class FlashSaleRedisLuaIT extends AbstractRedisIT {

    private static final DefaultRedisScript<Long> FLASH_SALE_SCRIPT = new DefaultRedisScript<>();

    static {
        FLASH_SALE_SCRIPT.setResultType(Long.class);
        FLASH_SALE_SCRIPT.setLocation(new ClassPathResource("flash-sale.lua"));
    }

    @Test
    void execute_whenOfferNotInitialized_returnsCode5() {
        Long result = execute("101", "501", "9001");

        assertThat(result).isEqualTo(5L);
    }

    @Test
    void execute_whenNotStarted_returnsCode3() {
        warmUpOffer("101", 10, nowPlusSeconds(60), nowPlusSeconds(3600));

        Long result = execute("101", "501", "9001");

        assertThat(result).isEqualTo(3L);
        assertThat(redisTemplate.opsForValue().get(stockKey("101"))).isEqualTo("10");
        assertThat(redisTemplate.opsForSet().isMember(orderKey("101"), "501")).isFalse();
    }

    @Test
    void execute_whenEnded_returnsCode4() {
        warmUpOffer("101", 10, nowPlusSeconds(-3600), nowPlusSeconds(-60));

        Long result = execute("101", "501", "9001");

        assertThat(result).isEqualTo(4L);
        assertThat(redisTemplate.opsForValue().get(stockKey("101"))).isEqualTo("10");
        assertThat(redisTemplate.opsForSet().isMember(orderKey("101"), "501")).isFalse();
    }

    @Test
    void execute_whenStockAvailable_reservesAndReturnsCode0() {
        warmUpOffer("101", 2, nowPlusSeconds(-60), nowPlusSeconds(3600));

        Long result = execute("101", "501", "9001");

        assertThat(result).isEqualTo(0L);
        assertThat(redisTemplate.opsForValue().get(stockKey("101"))).isEqualTo("1");
        assertThat(redisTemplate.opsForSet().isMember(orderKey("101"), "501")).isTrue();
        assertThat(redisTemplate.getExpire(stockKey("101"), TimeUnit.SECONDS)).isPositive();
        assertThat(redisTemplate.getExpire(offerKey("101"), TimeUnit.SECONDS)).isPositive();
        assertThat(redisTemplate.getExpire(orderKey("101"), TimeUnit.SECONDS)).isPositive();
    }

    @Test
    void execute_whenDuplicateUser_returnsCode2WithoutDecrementingStock() {
        warmUpOffer("101", 2, nowPlusSeconds(-60), nowPlusSeconds(3600));
        redisTemplate.opsForSet().add(orderKey("101"), "501");

        Long result = execute("101", "501", "9001");

        assertThat(result).isEqualTo(2L);
        assertThat(redisTemplate.opsForValue().get(stockKey("101"))).isEqualTo("2");
    }

    @Test
    void execute_whenStockEmpty_returnsCode1() {
        warmUpOffer("101", 0, nowPlusSeconds(-60), nowPlusSeconds(3600));

        Long result = execute("101", "501", "9001");

        assertThat(result).isEqualTo(1L);
        assertThat(redisTemplate.opsForSet().isMember(orderKey("101"), "501")).isFalse();
    }

    private Long execute(String offerId, String userId, String orderId) {
        return redisTemplate.execute(
                FLASH_SALE_SCRIPT,
                Collections.emptyList(),
                offerId,
                userId,
                orderId
        );
    }

    private void warmUpOffer(String offerId, int stock, long beginEpochSeconds, long endEpochSeconds) {
        redisTemplate.opsForValue().set(stockKey(offerId), String.valueOf(stock));
        redisTemplate.opsForHash().put(offerKey(offerId), "begin", String.valueOf(beginEpochSeconds));
        redisTemplate.opsForHash().put(offerKey(offerId), "end", String.valueOf(endEpochSeconds));
        redisTemplate.opsForHash().put(offerKey(offerId), "retainSeconds", "86400");
    }

    private long nowPlusSeconds(long seconds) {
        return Instant.now().plusSeconds(seconds).getEpochSecond();
    }

    private String stockKey(String offerId) {
        return FLASH_SALE_STOCK_KEY + offerId;
    }

    private String orderKey(String offerId) {
        return FLASH_SALE_ORDER_KEY + offerId;
    }

    private String offerKey(String offerId) {
        return FLASH_SALE_OFFER_KEY + offerId;
    }
}
