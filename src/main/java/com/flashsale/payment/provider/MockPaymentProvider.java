package com.flashsale.payment.provider;

import com.flashsale.payment.entity.PaymentOrder;
import com.flashsale.payment.enums.PaymentProviderType;
import com.flashsale.payment.enums.PaymentStatus;
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
