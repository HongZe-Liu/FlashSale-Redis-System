package com.flashsale.platform.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public static final long BEGIN_TIMESTAMP =
            LocalDateTime.of(2026, 1, 20, 0, 0)
                    .toEpochSecond(ZoneOffset.UTC);
    public static final long COUNT_BIT = 32L;

    public long nextId(String keyPrefix) {
        long currentSecond = Instant.now().getEpochSecond();
        long timeStamp = currentSecond - BEGIN_TIMESTAMP;

        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        String dateStr = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String redisKey = "inc:" + keyPrefix + ":" + dateStr;
        Long count = stringRedisTemplate.opsForValue().increment(redisKey);

        stringRedisTemplate.expire(redisKey, Duration.ofDays(2));

        return timeStamp << COUNT_BIT | count;
    }
}
