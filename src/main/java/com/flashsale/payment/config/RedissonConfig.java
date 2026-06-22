package com.flashsale.payment.config;
// redission 配置类

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
    @Value("${spring.redis.host}")
    private String host;
    @Value("${spring.redis.port}")
    private int port;

    @Bean
    public RedissonClient redissonClient() {

        // 1. 创建配置
        Config config = new Config();
        /**
         * 1. useSingleServer 连接redis（单机）
         * 2. setAddress 设置redis 连接地址
         * 3. setPassword(""); 设置redis 密码
         * 4. Redisson.create(config); 完成创建
         */
        String address = "redis://" + host + ":" + port;

        config.useSingleServer()
                .setAddress(address);

        return Redisson.create(config);
    }
}