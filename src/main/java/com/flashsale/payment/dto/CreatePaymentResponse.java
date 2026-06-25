package com.flashsale.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePaymentResponse {

    private Long paymentOrderId;

    private Long orderId;

    private String provider;

    private String providerPaymentId;

    private Long amount;

    private String currency;

    private String status;

    private String checkoutUrl;
}
