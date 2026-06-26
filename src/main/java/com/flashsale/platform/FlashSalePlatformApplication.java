package com.flashsale.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAspectJAutoProxy(exposeProxy = true)
@EnableScheduling
@MapperScan("com.flashsale.platform.mapper")
@SpringBootApplication
public class FlashSalePlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashSalePlatformApplication.class, args);
    }

}
