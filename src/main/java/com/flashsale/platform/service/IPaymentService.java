package com.flashsale.platform.service;

import com.flashsale.platform.dto.CreatePaymentRequest;
import com.flashsale.platform.dto.Result;

public interface IPaymentService {

    Result createPayment(Long orderId, Long userId, CreatePaymentRequest request);

    Result queryPaymentStatus(Long orderId, Long userId);
}
