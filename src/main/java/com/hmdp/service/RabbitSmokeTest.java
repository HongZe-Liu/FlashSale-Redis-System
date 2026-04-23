package com.hmdp.service;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitSmokeTest {

    @Bean
    CommandLineRunner rabbitSmoke(RabbitTemplate rabbitTemplate) {
        return args -> {
            rabbitTemplate.convertAndSend("amq.direct", "test.key", "hello rabbit");
            System.out.println("RabbitMQ send OK");
        };
    }
}