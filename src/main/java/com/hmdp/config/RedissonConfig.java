package com.hmdp.config;
// redission 配置类

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
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
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379");
        return Redisson.create(config);
    }
}
