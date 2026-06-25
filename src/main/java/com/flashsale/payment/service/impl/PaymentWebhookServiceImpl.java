package com.flashsale.payment.service.impl;

import com.flashsale.payment.dto.MockPaymentWebhookRequest;
import com.flashsale.payment.dto.Result;
import com.flashsale.payment.entity.Order;
import com.flashsale.payment.entity.PaymentOrder;
import com.flashsale.payment.entity.PaymentWebhookEvent;
import com.flashsale.payment.enums.OrderStatus;
import com.flashsale.payment.enums.PaymentProviderType;
import com.flashsale.payment.enums.PaymentStatus;
import com.flashsale.payment.enums.WebhookEventStatus;
import com.flashsale.payment.service.IOrderService;
import com.flashsale.payment.service.IPaymentOrderService;
import com.flashsale.payment.service.IPaymentWebhookEventService;
import com.flashsale.payment.service.IPaymentWebhookService;
import com.flashsale.payment.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Slf4j
@Service
public class PaymentWebhookServiceImpl implements IPaymentWebhookService {

    @Resource
    private IPaymentWebhookEventService paymentWebhookEventService;

    @Resource
    private IPaymentOrderService paymentOrderService;

    @Resource
    private IOrderService orderService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Override
    @Transactional
    public Result handleMockPaymentSucceeded(MockPaymentWebhookRequest request, String rawPayload) {
        Result validation = validateRequest(request);
        if (!Boolean.TRUE.equals(validation.getSuccess())) {
            return validation;
        }

        PaymentWebhookEvent event = buildProcessingEvent(request, rawPayload);
        try {
            paymentWebhookEventService.save(event);
        } catch (DuplicateKeyException e) {
            log.info("Mock支付webhook重复事件，直接按成功返回，eventId={}", request.getEventId());
            return Result.ok();
        }

        PaymentOrder paymentOrder = paymentOrderService.query()
                .eq("provider", PaymentProviderType.MOCK.name())
                .eq("provider_payment_id", request.getProviderPaymentId())
                .one();
        if (paymentOrder == null) {
            return markEventFailed(event, "支付单不存在");
        }

        Result businessValidation = validateBusinessPayload(request, paymentOrder);
        if (!Boolean.TRUE.equals(businessValidation.getSuccess())) {
            return markEventFailed(event, businessValidation.getErrorMsg());
        }

        Order order = orderService.getById(paymentOrder.getOrderId());
        if (order == null) {
            return markEventFailed(event, "订单不存在");
        }

        if (PaymentStatus.PAID.name().equals(paymentOrder.getStatus())
                && Integer.valueOf(OrderStatus.PAID.getCode()).equals(order.getStatus())) {
            markEventProcessed(event);
            return Result.ok();
        }

        if (!Integer.valueOf(OrderStatus.PENDING_PAYMENT.getCode()).equals(order.getStatus())) {
            return markEventFailed(event, "订单状态不允许支付");
        }

        LocalDateTime paidAt = LocalDateTime.now();
        boolean orderUpdated = orderService.update()
                .set("status", OrderStatus.PAID.getCode())
                .set("pay_time", paidAt)
                .eq("id", order.getId())
                .eq("status", OrderStatus.PENDING_PAYMENT.getCode())
                .update();
        if (!orderUpdated) {
            return markEventFailed(event, "订单状态已变化，支付成功事件未生效");
        }

        boolean paymentUpdated = paymentOrderService.update()
                .set("status", PaymentStatus.PAID.name())
                .set("paid_at", paidAt)
                .eq("id", paymentOrder.getId())
                .in("status", PaymentStatus.CREATED.name(), PaymentStatus.PENDING.name())
                .update();
        if (!paymentUpdated) {
            throw new IllegalStateException("支付单状态更新失败");
        }

        markEventProcessed(event);
        return Result.ok();
    }

    private Result validateRequest(MockPaymentWebhookRequest request) {
        if (request == null) {
            return Result.fail("webhook请求不能为空");
        }
        if (request.getEventId() == null || request.getEventId().isBlank()) {
            return Result.fail("eventId不能为空");
        }
        if (request.getProviderPaymentId() == null || request.getProviderPaymentId().isBlank()) {
            return Result.fail("providerPaymentId不能为空");
        }
        if (request.getOrderId() == null || request.getAmount() == null || request.getCurrency() == null) {
            return Result.fail("webhook业务参数不完整");
        }
        if (request.getEventType() == null || !"payment.succeeded".equals(request.getEventType())) {
            return Result.fail("不支持的mock webhook事件类型");
        }
        return Result.ok();
    }

    private Result validateBusinessPayload(MockPaymentWebhookRequest request, PaymentOrder paymentOrder) {
        if (!request.getOrderId().equals(paymentOrder.getOrderId())) {
            return Result.fail("webhook订单号不匹配");
        }
        if (!request.getAmount().equals(paymentOrder.getAmount())) {
            return Result.fail("webhook金额不匹配");
        }
        if (!request.getCurrency().equalsIgnoreCase(paymentOrder.getCurrency())) {
            return Result.fail("webhook币种不匹配");
        }
        return Result.ok();
    }

    private PaymentWebhookEvent buildProcessingEvent(MockPaymentWebhookRequest request, String rawPayload) {
        PaymentWebhookEvent event = new PaymentWebhookEvent();
        event.setId(redisIdWorker.nextId("webhook_event"));
        event.setProvider(PaymentProviderType.MOCK.name());
        event.setEventId(request.getEventId());
        event.setEventType(request.getEventType());
        event.setProviderPaymentId(request.getProviderPaymentId());
        event.setOrderId(request.getOrderId());
        event.setStatus(WebhookEventStatus.PROCESSING.name());
        event.setRawPayload(rawPayload);
        event.setCreateTime(LocalDateTime.now());
        return event;
    }

    private void markEventProcessed(PaymentWebhookEvent event) {
        event.setStatus(WebhookEventStatus.PROCESSED.name());
        event.setProcessedAt(LocalDateTime.now());
        paymentWebhookEventService.updateById(event);
    }

    private Result markEventFailed(PaymentWebhookEvent event, String errorMessage) {
        event.setStatus(WebhookEventStatus.FAILED.name());
        event.setErrorMessage(truncate(errorMessage));
        event.setProcessedAt(LocalDateTime.now());
        paymentWebhookEventService.updateById(event);
        return Result.fail(errorMessage);
    }

    private String truncate(String message) {
        if (message == null || message.length() <= 512) {
            return message;
        }
        return message.substring(0, 512);
    }
}
