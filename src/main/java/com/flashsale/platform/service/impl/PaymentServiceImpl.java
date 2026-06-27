package com.flashsale.platform.service.impl;

import com.flashsale.platform.config.PaymentProperties;
import com.flashsale.platform.dto.CreatePaymentRequest;
import com.flashsale.platform.dto.CreatePaymentResponse;
import com.flashsale.platform.dto.PaymentStatusResponse;
import com.flashsale.platform.dto.Result;
import com.flashsale.platform.entity.Order;
import com.flashsale.platform.entity.PaymentOrder;
import com.flashsale.platform.enums.OrderStatus;
import com.flashsale.platform.enums.PaymentProviderType;
import com.flashsale.platform.enums.PaymentStatus;
import com.flashsale.platform.observability.BusinessMetrics;
import com.flashsale.platform.provider.PaymentProvider;
import com.flashsale.platform.provider.PaymentProviderResult;
import com.flashsale.platform.service.IOrderService;
import com.flashsale.platform.service.IPaymentOrderService;
import com.flashsale.platform.service.IPaymentService;
import com.flashsale.platform.utils.RedisIdWorker;
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

    @Resource
    private BusinessMetrics businessMetrics;

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
            businessMetrics.recordPaymentCreateFailure("unknown", "invalid_arguments");
            return Result.fail("Order arguments are incomplete");
        }

        Order order = orderService.getById(orderId);
        PaymentValidation validation = validateOrderForPayment(order, userId);
        if (!validation.success) {
            businessMetrics.recordPaymentCreateFailure("unknown", validation.reason);
            return validation.result;
        }

        PaymentOrder existing = paymentOrderService.query()
                .eq("order_id", orderId)
                .one();
        if (existing != null) {
            if (isReusablePayment(existing)) {
                businessMetrics.recordPaymentCreateReused(providerOf(existing));
                return Result.ok(toCreatePaymentResponse(existing));
            }
            if (PaymentStatus.CREATED.name().equals(existing.getStatus())) {
                businessMetrics.recordPaymentCreateFailure(providerOf(existing), "payment_creating");
                return Result.fail("Payment order is being created; please retry later");
            }
            businessMetrics.recordPaymentCreateFailure(providerOf(existing), "payment_status_not_allowed");
            return Result.fail("Current payment status does not allow retry");
        }

        PaymentProviderType providerType = resolveProviderType(request);
        if (providerType == null) {
            businessMetrics.recordPaymentCreateFailure("unknown", "unsupported_provider");
            return Result.fail("Unsupported payment provider");
        }
        PaymentProvider provider = providerMap.get(providerType);
        if (provider == null) {
            businessMetrics.recordPaymentCreateFailure(providerType.name(), "provider_unavailable");
            return Result.fail("Payment provider is temporarily unavailable");
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
                businessMetrics.recordPaymentCreateFailure(providerType.name(), "save_failed");
                return Result.fail("Failed to create payment order");
            }
        } catch (DuplicateKeyException e) {
            PaymentOrder duplicated = paymentOrderService.query()
                    .eq("order_id", orderId)
                    .one();
            if (duplicated != null && isReusablePayment(duplicated)) {
                businessMetrics.recordPaymentCreateReused(providerOf(duplicated));
                return Result.ok(toCreatePaymentResponse(duplicated));
            }
            if (duplicated != null && PaymentStatus.CREATED.name().equals(duplicated.getStatus())) {
                businessMetrics.recordPaymentCreateFailure(providerOf(duplicated), "payment_creating");
                return Result.fail("Payment order is being created; please retry later");
            }
            businessMetrics.recordPaymentCreateFailure(providerType.name(), "duplicate_unresolved");
            throw e;
        } catch (RuntimeException e) {
            businessMetrics.recordPaymentCreateFailure(providerType.name(), "save_exception");
            throw e;
        }

        try {
            PaymentProviderResult providerResult = provider.createPayment(paymentOrder);
            paymentOrder.setProviderPaymentId(providerResult.getProviderPaymentId());
            paymentOrder.setCheckoutUrl(providerResult.getCheckoutUrl());
            paymentOrder.setStatus(providerResult.getStatus().name());
            boolean updated = paymentOrderService.updateById(paymentOrder);
            if (!updated) {
                throw new IllegalStateException("Failed to update provider payment details");
            }
            businessMetrics.recordPaymentCreateSuccess(providerType.name());
            return Result.ok(toCreatePaymentResponse(paymentOrder));
        } catch (Exception e) {
            log.error("Payment provider create call failed, orderId={}, paymentOrderId={}, provider={}",
                    orderId, paymentOrder.getId(), providerType, e);
            paymentOrder.setStatus(PaymentStatus.FAILED.name());
            paymentOrder.setFailureReason(e.getClass().getSimpleName());
            paymentOrderService.updateById(paymentOrder);
            businessMetrics.recordPaymentCreateFailure(providerType.name(), "provider_exception");
            return Result.fail("Failed to create payment; please retry later");
        }
    }

    @Override
    public Result queryPaymentStatus(Long orderId, Long userId) {
        if (orderId == null || userId == null) {
            return Result.fail("Order arguments are incomplete");
        }
        Order order = orderService.getById(orderId);
        if (order == null) {
            return Result.fail("Order does not exist");
        }
        if (!userId.equals(order.getUserId())) {
            return Result.fail("Not allowed to view this order payment status");
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

    private PaymentValidation validateOrderForPayment(Order order, Long userId) {
        if (order == null) {
            return PaymentValidation.failure("order_not_found", "Order does not exist");
        }
        if (!userId.equals(order.getUserId())) {
            return PaymentValidation.failure("forbidden_order_owner", "Not allowed to pay this order");
        }
        if (!Integer.valueOf(OrderStatus.PENDING_PAYMENT.getCode()).equals(order.getStatus())) {
            return PaymentValidation.failure("invalid_order_status", "Order status does not allow payment");
        }
        if (order.getExpireTime() != null && order.getExpireTime().isBefore(LocalDateTime.now())) {
            return PaymentValidation.failure("order_expired", "Order has expired; please wait for cancellation");
        }
        if (order.getPayAmount() == null || order.getPayAmount() <= 0) {
            return PaymentValidation.failure("invalid_amount", "Invalid order amount");
        }
        if (order.getCurrency() == null || order.getCurrency().isBlank()) {
            return PaymentValidation.failure("invalid_currency", "Invalid order currency");
        }
        return PaymentValidation.success();
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

    private String providerOf(PaymentOrder paymentOrder) {
        if (paymentOrder == null || paymentOrder.getProvider() == null || paymentOrder.getProvider().isBlank()) {
            return "unknown";
        }
        return paymentOrder.getProvider();
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

    private static class PaymentValidation {
        private final boolean success;
        private final String reason;
        private final Result result;

        private PaymentValidation(boolean success, String reason, Result result) {
            this.success = success;
            this.reason = reason;
            this.result = result;
        }

        private static PaymentValidation success() {
            return new PaymentValidation(true, null, Result.ok());
        }

        private static PaymentValidation failure(String reason, String message) {
            return new PaymentValidation(false, reason, Result.fail(message));
        }
    }
}
