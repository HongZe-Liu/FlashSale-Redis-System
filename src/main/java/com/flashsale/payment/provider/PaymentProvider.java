package com.flashsale.payment.provider;

import com.flashsale.payment.entity.PaymentOrder;
import com.flashsale.payment.enums.PaymentProviderType;

public interface PaymentProvider {

    PaymentProviderType providerType();

    PaymentProviderResult createPayment(PaymentOrder paymentOrder);
}
