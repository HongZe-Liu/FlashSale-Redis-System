package com.flashsale.platform.integration.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.flashsale.platform.config.RabbitMqConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
abstract class AbstractRabbitMqIT {

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management-alpine")
    );

    protected CachingConnectionFactory connectionFactory;
    protected RabbitAdmin rabbitAdmin;
    protected RabbitTemplate rabbitTemplate;
    protected RabbitMqConfig rabbitMqConfig;

    @BeforeEach
    void setUpRabbitMq() {
        connectionFactory = new CachingConnectionFactory(RABBITMQ.getHost(), RABBITMQ.getMappedPort(5672));
        connectionFactory.setUsername(RABBITMQ.getAdminUsername());
        connectionFactory.setPassword(RABBITMQ.getAdminPassword());
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        connectionFactory.afterPropertiesSet();

        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Jackson2JsonMessageConverter messageConverter = new Jackson2JsonMessageConverter(objectMapper);

        rabbitAdmin = new RabbitAdmin(connectionFactory);
        rabbitAdmin.setIgnoreDeclarationExceptions(false);

        rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);
        rabbitTemplate.setMandatory(true);

        rabbitMqConfig = new RabbitMqConfig();
        declareTopology();
        purgeQueues();
    }

    @AfterEach
    void tearDownRabbitMq() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    protected void declareTopology() {
        rabbitAdmin.declareExchange(rabbitMqConfig.flashSaleOrderExchange());
        rabbitAdmin.declareExchange(rabbitMqConfig.flashSaleOrderRetryExchange());
        rabbitAdmin.declareExchange(rabbitMqConfig.flashSaleOrderDeadExchange());

        rabbitAdmin.declareQueue(rabbitMqConfig.flashSaleOrderCreateQueue());
        rabbitAdmin.declareQueue(rabbitMqConfig.flashSaleOrderRetryQueue());
        rabbitAdmin.declareQueue(rabbitMqConfig.flashSaleOrderDeadQueue());

        rabbitAdmin.declareBinding(rabbitMqConfig.flashSaleOrderCreateBinding());
        rabbitAdmin.declareBinding(rabbitMqConfig.flashSaleOrderRetryBinding());
        rabbitAdmin.declareBinding(rabbitMqConfig.flashSaleOrderDeadBinding());
    }

    protected void purgeQueues() {
        rabbitAdmin.purgeQueue(RabbitMqConfig.ORDER_CREATE_QUEUE, true);
        rabbitAdmin.purgeQueue(RabbitMqConfig.ORDER_RETRY_QUEUE, true);
        rabbitAdmin.purgeQueue(RabbitMqConfig.ORDER_DEAD_QUEUE, true);
    }
}
