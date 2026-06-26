package com.flashsale.platform.provider;

import com.flashsale.platform.entity.PaymentOrder;
import com.flashsale.platform.enums.PaymentProviderType;
import com.flashsale.platform.enums.PaymentStatus;
import org.springframework.stereotype.Component;

@Component
public class MockPaymentProvider implements PaymentProvider {

    @Override
    public PaymentProviderType providerType() {
        return PaymentProviderType.MOCK;
    }

    @Override
    public PaymentProviderResult createPayment(PaymentOrder paymentOrder) {
        String providerPaymentId = "mock_pay_" + paymentOrder.getId();
        String checkoutUrl = "/payments/mock/checkout/" + providerPaymentId;
        return new PaymentProviderResult(providerPaymentId, checkoutUrl, PaymentStatus.PENDING);
    }
}
