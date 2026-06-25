package com.flashsale.payment.service;

import com.flashsale.payment.dto.CreatePaymentRequest;
import com.flashsale.payment.dto.Result;

public interface IPaymentService {

    Result createPayment(Long orderId, Long userId, CreatePaymentRequest request);

    Result queryPaymentStatus(Long orderId, Long userId);
}
