package com.hmdp.lock;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.security.PrivateKey;
import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的简单分布式锁实现
 *
 * 核心思路：
 * 1. 使用 Redis 的 SETNX（setIfAbsent）实现互斥加锁
 * 2. 加锁时设置过期时间，防止服务宕机导致死锁
 * 3. 锁的 value 使用“UUID + 线程ID”作为唯一标识（ownerId）
 *    - UUID：区分不同 JVM 实例
 *    - 线程ID：区分同一 JVM 内的不同线程
 * 4. 解锁时先校验当前线程是否为锁的持有者
 *    - 只有 ownerId 一致时才允许删除锁
 *    - 避免锁过期后误删其他线程新加的锁
 *
 * 注意：
 * - 本实现通过 value 校验避免误删锁
 * - 解锁使用 GET + DEL，存在极小并发窗口
 *   更严谨的做法是使用 Lua 脚本保证原子性
 */

public class SimpleRedisLock implements ILock {


    // 锁前缀
    private static final String KEY_PREFIX = "lock:";
    // 具体业务名称 -> 和锁前缀拼接组成key
    private String name;
    // 注入
    private StringRedisTemplate stringRedisTemplate;
    // 构造器
    public SimpleRedisLock(String name,StringRedisTemplate redisTemplate ) {
        this.stringRedisTemplate = redisTemplate;
        this.name = name;
    }

    // 设置uuid 唯一标识，用于释放锁时避免误删
    private  static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    @Override
    public boolean trylock(long timeoutSec) {
        // 获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁：使用SETNX 加锁 / 设置过期时间
        // setIFAbsent() -> 设置SETNX
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        // 拆箱Boolean 更加问稳妥
        return Boolean.TRUE.equals(success);
    };


    // 创建lua脚本的封装对象
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    // 初始化对象：创建lua脚本读取对象，设置lua脚本位置，设置脚本返回值类型
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public void unlock() {
        stringRedisTemplate.execute(UNLOCK_SCRIPT,  // UNLOCK_SCRIPT -> lua 脚本
                // Collections.singletonList(x): 快速创建一个“只包含一个元素、而且不能修改的 List”。
                // Redis Lua 要求所有 key 通过 KEYS 数组传入，而在 Java 中 KEYS 用 List 来表示
                Collections.singletonList(KEY_PREFIX + name), // 生成 key，(lua的第一个参数)
                ID_PREFIX + Thread.currentThread().getId()); // 生成锁的value：UUID +  线程id
    }
}
