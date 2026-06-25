package com.flashsale.payment.dto;

import lombok.Data;

@Data
public class MockPaymentWebhookRequest {

    private String eventId;

    private String eventType;

    private String providerPaymentId;

    private Long orderId;

    private Long amount;

    private String currency;
}
