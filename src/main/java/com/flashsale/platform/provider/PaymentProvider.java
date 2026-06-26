package com.flashsale.platform.provider;

import com.flashsale.platform.entity.PaymentOrder;
import com.flashsale.platform.enums.PaymentProviderType;

public interface PaymentProvider {

    PaymentProviderType providerType();

    PaymentProviderResult createPayment(PaymentOrder paymentOrder);
}
