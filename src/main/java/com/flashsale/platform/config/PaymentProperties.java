package com.flashsale.platform.config;

import com.flashsale.platform.enums.PaymentProviderType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.payment")
public class PaymentProperties {

    private PaymentProviderType provider = PaymentProviderType.MOCK;

    private long orderExpireMinutes = 15L;

    private long timeoutScanFixedDelayMs = 60000L;

    private int timeoutScanBatchSize = 100;

    private String mockWebhookSecret;
}
