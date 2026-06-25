package com.flashsale.payment.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashsale.payment.entity.PaymentWebhookEvent;
import com.flashsale.payment.mapper.PaymentWebhookEventMapper;
import com.flashsale.payment.service.IPaymentWebhookEventService;
import org.springframework.stereotype.Service;

@Service
public class PaymentWebhookEventServiceImpl
        extends ServiceImpl<PaymentWebhookEventMapper, PaymentWebhookEvent>
        implements IPaymentWebhookEventService {
}
