package com.flashsale.payment;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.flashsale.payment.mapper")
@SpringBootApplication
public class FlashSalePaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashSalePaymentApplication.class, args);
    }

}
