package com.flashsale.payment.controller;

import com.flashsale.payment.dto.CreatePaymentRequest;
import com.flashsale.payment.dto.Result;
import com.flashsale.payment.dto.UserDTO;
import com.flashsale.payment.service.IPaymentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    @Resource
    private IPaymentService paymentService;

    @PostMapping("/orders/{orderId}")
    public Result createPayment(@PathVariable("orderId") Long orderId,
                                @AuthenticationPrincipal UserDTO user,
                                @RequestBody(required = false) CreatePaymentRequest request) {
        if (user == null) {
            return Result.fail("用户未登录");
        }
        return paymentService.createPayment(orderId, user.getId(), request);
    }

    @GetMapping("/orders/{orderId}")
    public Result queryPaymentStatus(@PathVariable("orderId") Long orderId,
                                     @AuthenticationPrincipal UserDTO user) {
        if (user == null) {
            return Result.fail("用户未登录");
        }
        return paymentService.queryPaymentStatus(orderId, user.getId());
    }
}
