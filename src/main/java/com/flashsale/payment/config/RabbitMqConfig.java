package com.flashsale.payment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String ORDER_EXCHANGE = "flashsale.order.exchange";
    public static final String ORDER_CREATE_QUEUE = "flashsale.order.create.queue";
    public static final String ORDER_CREATE_ROUTING_KEY = "flashsale.order.create";

    public static final String ORDER_RETRY_EXCHANGE = "flashsale.order.retry.exchange";
    public static final String ORDER_RETRY_QUEUE = "flashsale.order.create.retry.queue";
    public static final String ORDER_RETRY_ROUTING_KEY = "flashsale.order.retry";
    public static final int ORDER_RETRY_TTL_MS = 5000;

    public static final String ORDER_DEAD_EXCHANGE = "flashsale.order.dead.exchange";
    public static final String ORDER_DEAD_QUEUE = "flashsale.order.create.dlq";
    public static final String ORDER_DEAD_ROUTING_KEY = "flashsale.order.dead";

    @Bean
    public DirectExchange flashSaleOrderExchange() {
        return new DirectExchange(ORDER_EXCHANGE, true, false);
    }

    @Bean
    public Queue flashSaleOrderCreateQueue() {
        return QueueBuilder.durable(ORDER_CREATE_QUEUE)
                .withArgument("x-dead-letter-exchange", ORDER_DEAD_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ORDER_DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding flashSaleOrderCreateBinding() {
        return BindingBuilder.bind(flashSaleOrderCreateQueue())
                .to(flashSaleOrderExchange())
                .with(ORDER_CREATE_ROUTING_KEY);
    }

    @Bean
    public DirectExchange flashSaleOrderRetryExchange() {
        return new DirectExchange(ORDER_RETRY_EXCHANGE, true, false);
    }

    @Bean
    public Queue flashSaleOrderRetryQueue() {
        return QueueBuilder.durable(ORDER_RETRY_QUEUE)
                .withArgument("x-message-ttl", ORDER_RETRY_TTL_MS)
                .withArgument("x-dead-letter-exchange", ORDER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ORDER_CREATE_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding flashSaleOrderRetryBinding() {
        return BindingBuilder.bind(flashSaleOrderRetryQueue())
                .to(flashSaleOrderRetryExchange())
                .with(ORDER_RETRY_ROUTING_KEY);
    }

    @Bean
    public DirectExchange flashSaleOrderDeadExchange() {
        return new DirectExchange(ORDER_DEAD_EXCHANGE, true, false);
    }

    @Bean
    public Queue flashSaleOrderDeadQueue() {
        return QueueBuilder.durable(ORDER_DEAD_QUEUE).build();
    }

    @Bean
    public Binding flashSaleOrderDeadBinding() {
        return BindingBuilder.bind(flashSaleOrderDeadQueue())
                .to(flashSaleOrderDeadExchange())
                .with(ORDER_DEAD_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
