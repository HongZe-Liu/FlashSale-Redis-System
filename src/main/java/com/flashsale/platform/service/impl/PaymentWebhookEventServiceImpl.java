package com.flashsale.platform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashsale.platform.entity.PaymentWebhookEvent;
import com.flashsale.platform.mapper.PaymentWebhookEventMapper;
import com.flashsale.platform.service.IPaymentWebhookEventService;
import org.springframework.stereotype.Service;

@Service
public class PaymentWebhookEventServiceImpl
        extends ServiceImpl<PaymentWebhookEventMapper, PaymentWebhookEvent>
        implements IPaymentWebhookEventService {
}
