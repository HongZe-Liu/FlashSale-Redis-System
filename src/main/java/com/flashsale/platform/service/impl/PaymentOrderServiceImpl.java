package com.flashsale.platform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashsale.platform.entity.PaymentOrder;
import com.flashsale.platform.mapper.PaymentOrderMapper;
import com.flashsale.platform.service.IPaymentOrderService;
import org.springframework.stereotype.Service;

@Service
public class PaymentOrderServiceImpl
        extends ServiceImpl<PaymentOrderMapper, PaymentOrder>
        implements IPaymentOrderService {
}
