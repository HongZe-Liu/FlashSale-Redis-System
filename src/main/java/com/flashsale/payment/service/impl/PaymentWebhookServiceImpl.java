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
import com.flashsale.payment.observability.BusinessMetrics;
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

    private static final String PROVIDER_MOCK = "MOCK";

    @Resource
    private IPaymentWebhookEventService paymentWebhookEventService;

    @Resource
    private IPaymentOrderService paymentOrderService;

    @Resource
    private IOrderService orderService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private BusinessMetrics businessMetrics;

    @Override
    @Transactional
    public Result handleMockPaymentSucceeded(MockPaymentWebhookRequest request, String rawPayload) {
        businessMetrics.recordPaymentWebhookReceived(PROVIDER_MOCK);
        WebhookValidation validation = validateRequest(request);
        if (!validation.success) {
            businessMetrics.recordPaymentWebhookFailure(PROVIDER_MOCK, validation.reason);
            return validation.result;
        }

        PaymentWebhookEvent event = buildProcessingEvent(request, rawPayload);
        try {
            paymentWebhookEventService.save(event);
        } catch (DuplicateKeyException e) {
            return handleDuplicateEvent(request);
        }

        PaymentOrder paymentOrder = paymentOrderService.query()
                .eq("provider", PaymentProviderType.MOCK.name())
                .eq("provider_payment_id", request.getProviderPaymentId())
                .one();
        if (paymentOrder == null) {
            return markEventFailed(event, "payment_order_not_found", "支付单不存在");
        }

        WebhookValidation businessValidation = validateBusinessPayload(request, paymentOrder);
        if (!businessValidation.success) {
            return markEventFailed(event, businessValidation.reason, businessValidation.result.getErrorMsg());
        }

        Order order = orderService.getById(paymentOrder.getOrderId());
        if (order == null) {
            return markEventFailed(event, "order_not_found", "订单不存在");
        }

        if (PaymentStatus.PAID.name().equals(paymentOrder.getStatus())
                && Integer.valueOf(OrderStatus.PAID.getCode()).equals(order.getStatus())) {
            markEventProcessed(event);
            businessMetrics.recordPaymentWebhookSuccess(PROVIDER_MOCK);
            return Result.ok();
        }

        if (!Integer.valueOf(OrderStatus.PENDING_PAYMENT.getCode()).equals(order.getStatus())) {
            return markEventFailed(event, "invalid_order_status", "订单状态不允许支付");
        }

        LocalDateTime paidAt = LocalDateTime.now();
        boolean orderUpdated = orderService.update()
                .set("status", OrderStatus.PAID.getCode())
                .set("pay_time", paidAt)
                .eq("id", order.getId())
                .eq("status", OrderStatus.PENDING_PAYMENT.getCode())
                .update();
        if (!orderUpdated) {
            return markEventFailed(event, "order_status_changed", "订单状态已变化，支付成功事件未生效");
        }

        boolean paymentUpdated = paymentOrderService.update()
                .set("status", PaymentStatus.PAID.name())
                .set("paid_at", paidAt)
                .eq("id", paymentOrder.getId())
                .in("status", PaymentStatus.CREATED.name(), PaymentStatus.PENDING.name())
                .update();
        if (!paymentUpdated) {
            businessMetrics.recordPaymentWebhookFailure(PROVIDER_MOCK, "payment_status_update_failed");
            throw new IllegalStateException("支付单状态更新失败");
        }

        markEventProcessed(event);
        businessMetrics.recordPaymentWebhookSuccess(PROVIDER_MOCK);
        return Result.ok();
    }

    private WebhookValidation validateRequest(MockPaymentWebhookRequest request) {
        if (request == null) {
            return WebhookValidation.failure("request_null", "webhook请求不能为空");
        }
        if (request.getEventId() == null || request.getEventId().isBlank()) {
            return WebhookValidation.failure("event_id_missing", "eventId不能为空");
        }
        if (request.getProviderPaymentId() == null || request.getProviderPaymentId().isBlank()) {
            return WebhookValidation.failure("provider_payment_id_missing", "providerPaymentId不能为空");
        }
        if (request.getOrderId() == null || request.getAmount() == null || request.getCurrency() == null) {
            return WebhookValidation.failure("business_arguments_missing", "webhook业务参数不完整");
        }
        if (request.getEventType() == null || !"payment.succeeded".equals(request.getEventType())) {
            return WebhookValidation.failure("unsupported_event_type", "不支持的mock webhook事件类型");
        }
        return WebhookValidation.success();
    }

    private WebhookValidation validateBusinessPayload(MockPaymentWebhookRequest request, PaymentOrder paymentOrder) {
        if (!request.getOrderId().equals(paymentOrder.getOrderId())) {
            return WebhookValidation.failure("order_id_mismatch", "webhook订单号不匹配");
        }
        if (!request.getAmount().equals(paymentOrder.getAmount())) {
            return WebhookValidation.failure("amount_mismatch", "webhook金额不匹配");
        }
        if (!request.getCurrency().equalsIgnoreCase(paymentOrder.getCurrency())) {
            return WebhookValidation.failure("currency_mismatch", "webhook币种不匹配");
        }
        return WebhookValidation.success();
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

    private Result handleDuplicateEvent(MockPaymentWebhookRequest request) {
        PaymentWebhookEvent existing = paymentWebhookEventService.query()
                .eq("provider", PROVIDER_MOCK)
                .eq("event_id", request.getEventId())
                .one();
        if (existing == null) {
            log.warn("Mock支付webhook重复事件未查询到原事件，eventId={}", request.getEventId());
            businessMetrics.recordPaymentWebhookDuplicate(PROVIDER_MOCK, "missing_original");
            return Result.fail("webhook重复事件状态未知，请稍后重试");
        }

        if (WebhookEventStatus.PROCESSED.name().equals(existing.getStatus())) {
            log.info("Mock支付webhook重复成功事件，按幂等成功返回，eventId={}", request.getEventId());
            businessMetrics.recordPaymentWebhookDuplicate(PROVIDER_MOCK, "processed");
            return Result.ok();
        }

        if (WebhookEventStatus.FAILED.name().equals(existing.getStatus())) {
            log.info("Mock支付webhook重复失败事件，按失败返回，eventId={}, error={}",
                    request.getEventId(), existing.getErrorMessage());
            businessMetrics.recordPaymentWebhookDuplicate(PROVIDER_MOCK, "failed");
            String errorMessage = existing.getErrorMessage();
            if (errorMessage == null || errorMessage.isBlank()) {
                errorMessage = "webhook事件已处理失败";
            }
            return Result.fail(errorMessage);
        }

        log.info("Mock支付webhook重复处理中事件，拒绝重复处理，eventId={}", request.getEventId());
        businessMetrics.recordPaymentWebhookDuplicate(PROVIDER_MOCK, "processing");
        return Result.fail("webhook事件正在处理中，请稍后重试");
    }

    private void markEventProcessed(PaymentWebhookEvent event) {
        event.setStatus(WebhookEventStatus.PROCESSED.name());
        event.setProcessedAt(LocalDateTime.now());
        paymentWebhookEventService.updateById(event);
    }

    private Result markEventFailed(PaymentWebhookEvent event, String reason, String errorMessage) {
        event.setStatus(WebhookEventStatus.FAILED.name());
        event.setErrorMessage(truncate(errorMessage));
        event.setProcessedAt(LocalDateTime.now());
        paymentWebhookEventService.updateById(event);
        businessMetrics.recordPaymentWebhookFailure(PROVIDER_MOCK, reason);
        return Result.fail(errorMessage);
    }

    private String truncate(String message) {
        if (message == null || message.length() <= 512) {
            return message;
        }
        return message.substring(0, 512);
    }

    private static class WebhookValidation {
        private final boolean success;
        private final String reason;
        private final Result result;

        private WebhookValidation(boolean success, String reason, Result result) {
            this.success = success;
            this.reason = reason;
            this.result = result;
        }

        private static WebhookValidation success() {
            return new WebhookValidation(true, null, Result.ok());
        }

        private static WebhookValidation failure(String reason, String message) {
            return new WebhookValidation(false, reason, Result.fail(message));
        }
    }
}
