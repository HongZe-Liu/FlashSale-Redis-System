package com.flashsale.payment.service.impl;

import com.flashsale.payment.config.PaymentProperties;
import com.flashsale.payment.dto.CreatePaymentRequest;
import com.flashsale.payment.dto.CreatePaymentResponse;
import com.flashsale.payment.dto.PaymentStatusResponse;
import com.flashsale.payment.dto.Result;
import com.flashsale.payment.entity.Order;
import com.flashsale.payment.entity.PaymentOrder;
import com.flashsale.payment.enums.OrderStatus;
import com.flashsale.payment.enums.PaymentProviderType;
import com.flashsale.payment.enums.PaymentStatus;
import com.flashsale.payment.provider.PaymentProvider;
import com.flashsale.payment.provider.PaymentProviderResult;
import com.flashsale.payment.service.IOrderService;
import com.flashsale.payment.service.IPaymentOrderService;
import com.flashsale.payment.service.IPaymentService;
import com.flashsale.payment.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class PaymentServiceImpl implements IPaymentService {

    @Resource
    private IOrderService orderService;

    @Resource
    private IPaymentOrderService paymentOrderService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private PaymentProperties paymentProperties;

    @Resource
    private List<PaymentProvider> paymentProviders;

    private final Map<PaymentProviderType, PaymentProvider> providerMap =
            new EnumMap<>(PaymentProviderType.class);

    @PostConstruct
    public void initProviders() {
        for (PaymentProvider provider : paymentProviders) {
            providerMap.put(provider.providerType(), provider);
        }
    }

    @Override
    @Transactional
    public Result createPayment(Long orderId, Long userId, CreatePaymentRequest request) {
        if (orderId == null || userId == null) {
            return Result.fail("订单参数不完整");
        }

        Order order = orderService.getById(orderId);
        Result validation = validateOrderForPayment(order, userId);
        if (!Boolean.TRUE.equals(validation.getSuccess())) {
            return validation;
        }

        PaymentOrder existing = paymentOrderService.query()
                .eq("order_id", orderId)
                .one();
        if (existing != null) {
            if (isReusablePayment(existing)) {
                return Result.ok(toCreatePaymentResponse(existing));
            }
            if (PaymentStatus.CREATED.name().equals(existing.getStatus())) {
                return Result.fail("支付单创建中，请稍后重试");
            }
            return Result.fail("当前支付单状态不允许继续支付");
        }

        PaymentProviderType providerType = resolveProviderType(request);
        if (providerType == null) {
            return Result.fail("不支持的支付渠道");
        }
        PaymentProvider provider = providerMap.get(providerType);
        if (provider == null) {
            return Result.fail("支付渠道暂不可用");
        }

        PaymentOrder paymentOrder = new PaymentOrder();
        paymentOrder.setId(redisIdWorker.nextId("payment"));
        paymentOrder.setOrderId(order.getId());
        paymentOrder.setUserId(userId);
        paymentOrder.setProvider(providerType.name());
        paymentOrder.setAmount(order.getPayAmount());
        paymentOrder.setCurrency(order.getCurrency());
        paymentOrder.setStatus(PaymentStatus.CREATED.name());
        paymentOrder.setExpiresAt(order.getExpireTime());
        paymentOrder.setCreateTime(LocalDateTime.now());

        try {
            boolean saved = paymentOrderService.save(paymentOrder);
            if (!saved) {
                return Result.fail("创建支付单失败");
            }
        } catch (DuplicateKeyException e) {
            PaymentOrder duplicated = paymentOrderService.query()
                    .eq("order_id", orderId)
                    .one();
            if (duplicated != null && isReusablePayment(duplicated)) {
                return Result.ok(toCreatePaymentResponse(duplicated));
            }
            if (duplicated != null && PaymentStatus.CREATED.name().equals(duplicated.getStatus())) {
                return Result.fail("支付单创建中，请稍后重试");
            }
            throw e;
        }

        try {
            PaymentProviderResult providerResult = provider.createPayment(paymentOrder);
            paymentOrder.setProviderPaymentId(providerResult.getProviderPaymentId());
            paymentOrder.setCheckoutUrl(providerResult.getCheckoutUrl());
            paymentOrder.setStatus(providerResult.getStatus().name());
            boolean updated = paymentOrderService.updateById(paymentOrder);
            if (!updated) {
                throw new IllegalStateException("更新支付单provider信息失败");
            }
            return Result.ok(toCreatePaymentResponse(paymentOrder));
        } catch (Exception e) {
            log.error("创建第三方支付失败，orderId={}, paymentOrderId={}, provider={}",
                    orderId, paymentOrder.getId(), providerType, e);
            paymentOrder.setStatus(PaymentStatus.FAILED.name());
            paymentOrder.setFailureReason(e.getClass().getSimpleName());
            paymentOrderService.updateById(paymentOrder);
            return Result.fail("创建支付失败，请稍后重试");
        }
    }

    @Override
    public Result queryPaymentStatus(Long orderId, Long userId) {
        if (orderId == null || userId == null) {
            return Result.fail("订单参数不完整");
        }
        Order order = orderService.getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!userId.equals(order.getUserId())) {
            return Result.fail("无权查看该订单支付状态");
        }
        PaymentOrder paymentOrder = paymentOrderService.query()
                .eq("order_id", orderId)
                .one();
        OrderStatus orderStatus = OrderStatus.fromCode(order.getStatus());
        PaymentStatusResponse response = new PaymentStatusResponse(
                order.getId(),
                orderStatus == null ? String.valueOf(order.getStatus()) : orderStatus.name(),
                paymentOrder == null ? null : paymentOrder.getStatus(),
                paymentOrder == null ? null : paymentOrder.getProvider(),
                order.getPayAmount(),
                order.getCurrency()
        );
        return Result.ok(response);
    }

    private Result validateOrderForPayment(Order order, Long userId) {
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!userId.equals(order.getUserId())) {
            return Result.fail("无权支付该订单");
        }
        if (!Integer.valueOf(OrderStatus.PENDING_PAYMENT.getCode()).equals(order.getStatus())) {
            return Result.fail("订单状态不允许支付");
        }
        if (order.getExpireTime() != null && order.getExpireTime().isBefore(LocalDateTime.now())) {
            return Result.fail("订单已超时，请等待系统取消");
        }
        if (order.getPayAmount() == null || order.getPayAmount() <= 0) {
            return Result.fail("订单金额异常");
        }
        if (order.getCurrency() == null || order.getCurrency().isBlank()) {
            return Result.fail("订单币种异常");
        }
        return Result.ok();
    }

    private PaymentProviderType resolveProviderType(CreatePaymentRequest request) {
        if (request == null || request.getProvider() == null || request.getProvider().isBlank()) {
            return paymentProperties.getProvider();
        }
        return PaymentProviderType.fromName(request.getProvider());
    }

    private boolean isReusablePayment(PaymentOrder paymentOrder) {
        return PaymentStatus.PENDING.name().equals(paymentOrder.getStatus())
                || PaymentStatus.PAID.name().equals(paymentOrder.getStatus());
    }

    private CreatePaymentResponse toCreatePaymentResponse(PaymentOrder paymentOrder) {
        return new CreatePaymentResponse(
                paymentOrder.getId(),
                paymentOrder.getOrderId(),
                paymentOrder.getProvider(),
                paymentOrder.getProviderPaymentId(),
                paymentOrder.getAmount(),
                paymentOrder.getCurrency(),
                paymentOrder.getStatus(),
                paymentOrder.getCheckoutUrl()
        );
    }
}
