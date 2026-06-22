package com.flashsale.payment.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
// 分布式全局ID生成器 -> 生成一个64 long 的id

/*
 * 分布式全局 ID 生成逻辑说明：
 *
 * 1. 使用 UTC 时间获取当前秒级时间戳，
 *    并减去自定义的起始时间 BEGIN_TIMESTAMP，
 *    得到一个从 0 开始递增的相对时间戳（高位部分）。
 *
 * 2. 以 UTC 日期（yyyy-MM-dd）作为 Redis Key 的一部分，
 *    每天生成一个新的自增序列，
 *    通过 Redis 的 INCR 命令保证分布式环境下的原子性和唯一性。
 *
 * 3. 将时间戳左移 COUNT_BIT 位，
 *    为低位序列号预留空间，
 *    再与当天的自增序列号进行位或运算，
 *    最终生成一个 64 位 long 类型的全局唯一 ID。
 *
 * 4. Redis 中的序列号 Key 设置过期时间，
 *    防止 Key 无限增长并控制序列号范围。
 */

@Component
public class RedisIdWorker {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    // 起始时间 UTC
    public static final long BEGIN_TIMESTAMP =
            LocalDateTime.of(2026, 1, 20, 0, 0)
                    .toEpochSecond(ZoneOffset.UTC);
    // 序列号长度
    public static final long COUNT_BIT = 32l;

    // 生成ID方法： nextId
    public long nextId(String keyPrefix) {
        // 当前时间
        long currentScond = Instant.now().getEpochSecond();
        // 1. 当前时间戳 -> 现在时间 - 过去时间
        long timeStamp = currentScond  - BEGIN_TIMESTAMP;

        // 2. 当前序列号
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        String dateStr = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String redisKey = "inc:" + keyPrefix + ":" + dateStr;
        Long count = stringRedisTemplate.opsForValue().increment(redisKey);

        // 3. 设置key过期，防止无限增长
        stringRedisTemplate.expire(redisKey, Duration.ofDays(2));

        // 3. 拼接并返回
        return timeStamp << COUNT_BIT | count;
    }
}
