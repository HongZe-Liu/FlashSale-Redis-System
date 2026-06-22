package com.flashsale.payment.utils;

// 封装redis缓存的问题解决策略（缓存穿透 / 缓存雪崩 / 缓存击穿）

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.flashsale.payment.entity.Merchant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.PrivateKey;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.flashsale.payment.utils.RedisConstants.CACHE_NULL_TTL;
import static com.flashsale.payment.utils.RedisConstants.LOCK_SHOP_KEY;
@Slf4j

@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate; // redis

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10); // 线程池

    // 方法1 ：将任意Java对象序列化为JSON，并存储到String类型的Key中，并可以设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit timeUnit ) {
     stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    // 方法2 ： 将任意Java对象序列化为JSON，并存储在String类型的Key中，并可以设置逻辑过期时间，用于处理缓存击穿问题
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit timeUnit ) {
        RedisData<Object> redisData = new RedisData<>();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 方法 3 ： 创建锁方法
     private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    // 方法 4 ： 解锁方法
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    // 方法 5: 根据指定的Key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    public <R, ID> R queryWithPassThrough(
            String keyPrefix,
            ID id,
            Class<R> type,
            Function<ID, R> dbFallback,
            Long time,
            TimeUnit timeUnit
    ) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 1. 命中缓存（真实数据）
        if (StrUtil.isNotBlank(json)) {
            log.info("[cache] hit key={}", key);
            return JSONUtil.toBean(json, type);
        }

        // 2. 命中空值缓存
        if (json != null) {
            log.info("[cache] null-hit key={}", key);
            return null;
        }
        log.info("[cache] miss key={}, query db", key);
        // 3. 查询数据库
        R r = dbFallback.apply(id);

        // 4. 数据库不存在 → 写空值缓存
        if (r == null) {
            stringRedisTemplate.opsForValue()
                    .set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 5. 数据库存在 → 写正常缓存
        this.set(key, r, time, timeUnit);
        return r;
    }

    // 方法 6: 根据指定的Key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id,Class<R> type, Function<ID, R> dbFallback,long time, TimeUnit timeUnit) {
        // 从redis中查询
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        // 未命中 -> 返回空值
        if(StrUtil.isBlank(json)) {
            return null;
        }
        // 命中 -> 将json反序列化为对象，判断是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 判断是否过期,未过期直接返回商铺数据
        if(expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }

        String lockKey = LOCK_SHOP_KEY + id;
        boolean flag = tryLock(lockKey);
        // 获取了锁
        if (flag) {
            // 开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R tmp = dbFallback.apply(id);
                    this.setWithLogicExpire(key, tmp, time, timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
            // 未获取锁 -> 直接返回商铺信息
        }
        return r;
    }

    // 方法7 : 根据指定的Key查询缓存，并反序列化为指定类型，需要利用互斥锁解决缓存击穿问题
    public <R,ID> R quertWithMutex(String keyPrefix , ID id , Class<R> type, Function<ID, R> dbFallback, long time, TimeUnit timeUnit ) {
        String key = keyPrefix + id;
        // 查询redis 是否存在
        String json = stringRedisTemplate.opsForValue().get(key);
        // 找到 -> 转为目标对象返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        //  命中空值缓存（""），直接返回 null，避免缓存穿透
        if (json != null) {
            return null;
        }
        R r = null; // 接数据库查询结果
        String lockKey = LOCK_SHOP_KEY + id;
        boolean locked = false;
        try {
            locked = tryLock(lockKey); // 确认是否当前有锁
            // 未获取到锁,等待并递归循环
            if (!locked) {
                Thread.sleep(50);
                return quertWithMutex(keyPrefix, id, type, dbFallback, time, timeUnit);
            }
            // 获取到锁开始查找
            r = dbFallback.apply(id);
            // 查不到则将空值写入redis
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 找到了则存入redis
            this.set(key, r, time, timeUnit);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (locked) unlock(lockKey);
        }
        return r;
    }

}
