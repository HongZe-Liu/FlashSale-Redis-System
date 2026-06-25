package com.flashsale.payment.service;

import com.flashsale.payment.dto.MockPaymentWebhookRequest;
import com.flashsale.payment.dto.Result;

public interface IPaymentWebhookService {

    Result handleMockPaymentSucceeded(MockPaymentWebhookRequest request, String rawPayload);
}
