package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;


import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component
// 缓存工具类
public class CacheClient1 {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient1(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public static final ExecutorService CACHE_REBUILD_EXECUTOR1 = Executors.newFixedThreadPool(10);

    // 1. 写入层

    // 1.1 普通写入
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(
                key,
                JSONUtil.toJsonStr(value),
                time,
                unit
        );
    }

    // 1.2 逻辑过期写入
    // redis 中存储包装对象 : 真实对象 + 逻辑过期时间
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData<Object> redisData = new RedisData<>();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(
                key,
                JSONUtil.toJsonStr(redisData)
        );
    }

    // 2.基础锁层

    // 2.1 尝试加锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue()
                //  value "1" 为占位,表示这个锁 key 是否已经存在
                // 在检查的时候如果value有1则证明有锁
                .setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    // 2.2 释放锁
    private void unLock(String key){
        // 直接删除key就释放了锁
        stringRedisTemplate.delete(key);
    }

    // 3. 缓存策略查询层(缓存问题处理方案)

    // 3.1 缓存穿透
    // -> 把空结果缓存起来
    // -> 后面相同请求直接被空值缓存挡住
    public <R,ID> R queryWithPassThrough(
            String keyPreflex, // key 前缀
            ID id, // 业务id
            Class<R> type, // 返回对象类型
            Function<ID,R> dbFallback, // 回查方法(如果 Redis 没查到，就调用这个函数去查数据库)
            Long time,
            TimeUnit timeUnit
    ){
        // 拼接key
        String key = keyPreflex + id;
        // 查Redis
        String json = stringRedisTemplate.opsForValue().get(key);
        // 命中真实数据 (非空字符串)
        if(StrUtil.isBlank(json)){
            // 反序列化直接返回
            return JSONUtil.toBean(json,type);
        }
        // 命中真实数据 (不是 null，但又不是非空字符串)
        // 说明命中的是空值缓存 ""
        if(json != null){
            return null;
        }
        // 未命中先查数据库
        R r = dbFallback.apply(id);
        // 如果数据库也查不到redis 写入空值,防止穿透
        if(r == null){
            stringRedisTemplate.opsForValue()
                    .set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
        }
        // 如果数据库查到数据则直接写入
        this.set(key,r,time,timeUnit);
        return r;
    }

    // 3.2 缓存击穿 -> 逻辑过期方案
    public <R,ID> R queryWithLogicalExpire(
            String keyPrefix, // key 前缀
            ID id, // 业务id
            Class<R> type, // 返回对象类型
            Function<ID,R> dbFallback, // 回查方法(如果 Redis 没查到，就调用这个函数去查数据库)
            Long time,
            TimeUnit timeUnit
    ){
        // 拼接key
        String key = keyPrefix + id;
        // 查redis
        String json = stringRedisTemplate.opsForValue().get(key);
        // 未命中直接返回
        // 逻辑过期方案缓存中至少有一份旧的数据
        if(StrUtil.isBlank(json)){
            return null;
        }
        // 反序列化
        // 字符串转RedisData
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        // 取出并转换为真实业务对象
        R r = JSONUtil.toBean((JSONObject) redisData.getData(),type);
        // 判断是否过期
        LocalDateTime expireTime = redisData.getExpireTime();
        // 没过期直接返回数据
        if(expireTime.isAfter(LocalDateTime.now())){
            return r;
        }
        // 过期尝试获取锁
        String lockKey = LOCK_SHOP_KEY  + id ;
        boolean flag = tryLock(lockKey);
        // 如果加锁成功后台重建缓存 + 先返回当前的数据
        if(flag){
            // 调用线程池中线程重建缓存
            CACHE_REBUILD_EXECUTOR1.submit(() -> {
                try {
                    // 查库
                    R temp = dbFallback.apply(id);
                    // 重新写入“新数据 + 新逻辑过期时间”
                    this.setWithLogicalExpire(key,temp,time,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 解锁
                    unLock(lockKey);
                }
            });
        }
        return r;
    }

    // 3.3 缓存击穿 -> 互斥锁方案
    // 缓存 miss 后,只让一个线程查数据库并重建缓存,其他线程等待或重试
    public <R,ID> R queryWithMutex(
            String keyPrefix, // key 前缀
            ID id, // 业务id
            Class<R> type, // 返回对象类型
            Function<ID,R> dbFallback, // 回查方法(如果 Redis 没查到，就调用这个函数去查数据库)
            Long time,
            TimeUnit timeUnit
    ){
        // 查Redis
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 命中(非空值) —> 直接返回
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json,type);
        }
        // 命中(空值) -> 返回null,防止缓存穿透
        if(json != null){
            return null;
        }
        // 准备加锁
        R r = null;
        String lockKey = LOCK_SHOP_KEY + id;
        boolean locked = false;
        try {
            // 尝试获取锁,保证唯一性
            locked = tryLock(lockKey);
            // 如果没拿到锁则等待递归重试
            if (!locked) {
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, timeUnit);
            }
            // 拿到锁查库
            r = dbFallback.apply(id);
            // 数据库没有则返回空值, 用于防穿透
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 数据库有数据正常写缓存
            this.set(key, r, time, timeUnit);
        }catch (InterruptedException e){
            throw new RuntimeException(e);
        }finally {
            if(locked){
                unLock(lockKey);
            }
        }
        return r;
    }

}
