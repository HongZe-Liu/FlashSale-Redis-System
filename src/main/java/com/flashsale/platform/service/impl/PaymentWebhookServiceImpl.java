package com.flashsale.platform.service.impl;

import com.flashsale.platform.dto.MockPaymentWebhookRequest;
import com.flashsale.platform.dto.Result;
import com.flashsale.platform.entity.Order;
import com.flashsale.platform.entity.PaymentOrder;
import com.flashsale.platform.entity.PaymentWebhookEvent;
import com.flashsale.platform.enums.OrderStatus;
import com.flashsale.platform.enums.PaymentProviderType;
import com.flashsale.platform.enums.PaymentStatus;
import com.flashsale.platform.enums.WebhookEventStatus;
import com.flashsale.platform.observability.BusinessMetrics;
import com.flashsale.platform.service.IOrderService;
import com.flashsale.platform.service.IPaymentOrderService;
import com.flashsale.platform.service.IPaymentWebhookEventService;
import com.flashsale.platform.service.IPaymentWebhookService;
import com.flashsale.platform.utils.RedisIdWorker;
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
            return markEventFailed(event, "payment_order_not_found", "Payment order does not exist");
        }

        WebhookValidation businessValidation = validateBusinessPayload(request, paymentOrder);
        if (!businessValidation.success) {
            return markEventFailed(event, businessValidation.reason, businessValidation.result.getErrorMsg());
        }

        Order order = orderService.getById(paymentOrder.getOrderId());
        if (order == null) {
            return markEventFailed(event, "order_not_found", "Order does not exist");
        }

        if (PaymentStatus.PAID.name().equals(paymentOrder.getStatus())
                && Integer.valueOf(OrderStatus.PAID.getCode()).equals(order.getStatus())) {
            markEventProcessed(event);
            businessMetrics.recordPaymentWebhookSuccess(PROVIDER_MOCK);
            return Result.ok();
        }

        if (!Integer.valueOf(OrderStatus.PENDING_PAYMENT.getCode()).equals(order.getStatus())) {
            return markEventFailed(event, "invalid_order_status", "Order status does not allow payment");
        }

        LocalDateTime paidAt = LocalDateTime.now();
        boolean orderUpdated = orderService.update()
                .set("status", OrderStatus.PAID.getCode())
                .set("pay_time", paidAt)
                .eq("id", order.getId())
                .eq("status", OrderStatus.PENDING_PAYMENT.getCode())
                .update();
        if (!orderUpdated) {
            return markEventFailed(event, "order_status_changed", "Order status changed; payment success event was not applied");
        }

        boolean paymentUpdated = paymentOrderService.update()
                .set("status", PaymentStatus.PAID.name())
                .set("paid_at", paidAt)
                .eq("id", paymentOrder.getId())
                .in("status", PaymentStatus.CREATED.name(), PaymentStatus.PENDING.name())
                .update();
        if (!paymentUpdated) {
            businessMetrics.recordPaymentWebhookFailure(PROVIDER_MOCK, "payment_status_update_failed");
            throw new IllegalStateException("Failed to update payment order status");
        }

        markEventProcessed(event);
        businessMetrics.recordPaymentWebhookSuccess(PROVIDER_MOCK);
        return Result.ok();
    }

    private WebhookValidation validateRequest(MockPaymentWebhookRequest request) {
        if (request == null) {
            return WebhookValidation.failure("request_null", "Webhook request must not be null");
        }
        if (request.getEventId() == null || request.getEventId().isBlank()) {
            return WebhookValidation.failure("event_id_missing", "eventId must not be blank");
        }
        if (request.getProviderPaymentId() == null || request.getProviderPaymentId().isBlank()) {
            return WebhookValidation.failure("provider_payment_id_missing", "providerPaymentId must not be blank");
        }
        if (request.getOrderId() == null || request.getAmount() == null || request.getCurrency() == null) {
            return WebhookValidation.failure("business_arguments_missing", "Webhook business arguments are incomplete");
        }
        if (request.getEventType() == null || !"payment.succeeded".equals(request.getEventType())) {
            return WebhookValidation.failure("unsupported_event_type", "Unsupported mock webhook event type");
        }
        return WebhookValidation.success();
    }

    private WebhookValidation validateBusinessPayload(MockPaymentWebhookRequest request, PaymentOrder paymentOrder) {
        if (!request.getOrderId().equals(paymentOrder.getOrderId())) {
            return WebhookValidation.failure("order_id_mismatch", "Webhook order id mismatch");
        }
        if (!request.getAmount().equals(paymentOrder.getAmount())) {
            return WebhookValidation.failure("amount_mismatch", "Webhook amount mismatch");
        }
        if (!request.getCurrency().equalsIgnoreCase(paymentOrder.getCurrency())) {
            return WebhookValidation.failure("currency_mismatch", "Webhook currency mismatch");
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
            log.warn("Duplicate mock payment webhook has no original event, eventId={}", request.getEventId());
            businessMetrics.recordPaymentWebhookDuplicate(PROVIDER_MOCK, "missing_original");
            return Result.fail("Duplicate webhook event state is unknown; please retry later");
        }

        if (WebhookEventStatus.PROCESSED.name().equals(existing.getStatus())) {
            log.info("Duplicate mock payment webhook was already processed, eventId={}", request.getEventId());
            businessMetrics.recordPaymentWebhookDuplicate(PROVIDER_MOCK, "processed");
            return Result.ok();
        }

        if (WebhookEventStatus.FAILED.name().equals(existing.getStatus())) {
            log.info("Duplicate mock payment webhook was already failed, eventId={}, error={}",
                    request.getEventId(), existing.getErrorMessage());
            businessMetrics.recordPaymentWebhookDuplicate(PROVIDER_MOCK, "failed");
            String errorMessage = existing.getErrorMessage();
            if (errorMessage == null || errorMessage.isBlank()) {
                errorMessage = "Webhook event was already processed as failed";
            }
            return Result.fail(errorMessage);
        }

        log.info("Duplicate mock payment webhook is still processing, eventId={}", request.getEventId());
        businessMetrics.recordPaymentWebhookDuplicate(PROVIDER_MOCK, "processing");
        return Result.fail("Webhook event is already being processed; please retry later");
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
