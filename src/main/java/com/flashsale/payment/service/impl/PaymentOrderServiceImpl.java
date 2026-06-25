package com.flashsale.payment.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashsale.payment.entity.PaymentOrder;
import com.flashsale.payment.mapper.PaymentOrderMapper;
import com.flashsale.payment.service.IPaymentOrderService;
import org.springframework.stereotype.Service;

@Service
public class PaymentOrderServiceImpl
        extends ServiceImpl<PaymentOrderMapper, PaymentOrder>
        implements IPaymentOrderService {
}
