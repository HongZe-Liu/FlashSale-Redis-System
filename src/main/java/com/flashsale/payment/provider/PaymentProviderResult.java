package com.flashsale.payment.provider;

import com.flashsale.payment.enums.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentProviderResult {

    private String providerPaymentId;

    private String checkoutUrl;

    private PaymentStatus status;
}
