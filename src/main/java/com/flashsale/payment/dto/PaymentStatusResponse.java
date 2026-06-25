package com.flashsale.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentStatusResponse {

    private Long orderId;

    private String orderStatus;

    private String paymentStatus;

    private String provider;

    private Long amount;

    private String currency;
}
