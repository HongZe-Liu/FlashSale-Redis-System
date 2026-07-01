package com.flashsale.platform.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.platform.config.PaymentProperties;
import com.flashsale.platform.dto.MockPaymentWebhookRequest;
import com.flashsale.platform.dto.Result;
import com.flashsale.platform.service.IPaymentWebhookService;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

@RestController
@RequestMapping("/payments/webhooks")
public class PaymentWebhookController {

    @Resource
    private IPaymentWebhookService paymentWebhookService;

    @Resource
    private PaymentProperties paymentProperties;

    @Resource
    private Environment environment;

    @Resource
    private ObjectMapper objectMapper;

    @PostMapping("/mock")
    public Result mockPaymentSucceeded(@RequestBody MockPaymentWebhookRequest request,
                                       @RequestHeader(value = "X-Mock-Webhook-Secret", required = false)
                                       String mockWebhookSecret) {
        if (!isMockWebhookAllowed(mockWebhookSecret)) {
            return Result.fail("Mock webhook is disabled");
        }
        return paymentWebhookService.handleMockPaymentSucceeded(request, toRawPayload(request));
    }

    private boolean isMockWebhookAllowed(String mockWebhookSecret) {
        if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
            return true;
        }
        String configuredSecret = paymentProperties.getMockWebhookSecret();
        return configuredSecret != null
                && !configuredSecret.isBlank()
                && configuredSecret.equals(mockWebhookSecret);
    }

    private String toRawPayload(MockPaymentWebhookRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            return String.valueOf(request);
        }
    }
}
