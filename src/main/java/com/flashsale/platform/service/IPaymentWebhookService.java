package com.flashsale.platform.service;

import com.flashsale.platform.dto.MockPaymentWebhookRequest;
import com.flashsale.platform.dto.Result;

public interface IPaymentWebhookService {

    Result handleMockPaymentSucceeded(MockPaymentWebhookRequest request, String rawPayload);
}
