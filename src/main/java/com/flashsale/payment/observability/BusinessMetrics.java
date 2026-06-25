package com.flashsale.payment.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class BusinessMetrics {

    private static final String TAG_REASON = "reason";
    private static final String TAG_PROVIDER = "provider";
    private static final String TAG_STATUS = "status";
    private static final String TAG_DESTINATION = "destination";

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry meterRegistry;

    public BusinessMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordLoginSuccess() {
        increment("flashsale.auth.login.success");
    }

    public void recordLoginFailure(String reason) {
        increment("flashsale.auth.login.failure", TAG_REASON, normalize(reason));
    }

    public void recordCodeSent() {
        increment("flashsale.auth.code.sent");
    }

    public void recordCodeRateLimited() {
        increment("flashsale.auth.code.rate_limited");
    }

    public void recordFlashSaleRequestTotal() {
        increment("flashsale.order.request");
    }

    public void recordFlashSaleRequestSuccess() {
        increment("flashsale.order.request.success");
    }

    public void recordFlashSaleRequestFailure(String reason) {
        increment("flashsale.order.request.failure", TAG_REASON, normalize(reason));
    }

    public void recordMqPublishSuccess(String destination) {
        increment("flashsale.mq.publish.success", TAG_DESTINATION, normalize(destination));
    }

    public void recordMqPublishFailure(String destination, String reason) {
        increment("flashsale.mq.publish.failure",
                TAG_DESTINATION, normalize(destination),
                TAG_REASON, normalize(reason));
    }

    public void recordMqConsumeSuccess() {
        increment("flashsale.mq.consume.success");
    }

    public void recordMqConsumeFailure(String reason) {
        increment("flashsale.mq.consume.failure", TAG_REASON, normalize(reason));
    }

    public void recordMqDeadLetter(String reason) {
        increment("flashsale.mq.dead_letter", TAG_REASON, normalize(reason));
    }

    public void recordOrderCreateSuccess() {
        increment("flashsale.order.create.success");
    }

    public void recordOrderCreateIdempotent() {
        increment("flashsale.order.create.idempotent");
    }

    public void recordOrderCreateFailure(String reason) {
        increment("flashsale.order.create.failure", TAG_REASON, normalize(reason));
    }

    public void recordOrderTimeoutExpired() {
        increment("flashsale.order.timeout.expired");
    }

    public void recordPaymentCreateSuccess(String provider) {
        increment("flashsale.payment.create.success", TAG_PROVIDER, normalize(provider));
    }

    public void recordPaymentCreateReused(String provider) {
        increment("flashsale.payment.create.reused", TAG_PROVIDER, normalize(provider));
    }

    public void recordPaymentCreateFailure(String provider, String reason) {
        increment("flashsale.payment.create.failure",
                TAG_PROVIDER, normalize(provider),
                TAG_REASON, normalize(reason));
    }

    public void recordPaymentWebhookReceived(String provider) {
        increment("flashsale.payment.webhook.received", TAG_PROVIDER, normalize(provider));
    }

    public void recordPaymentWebhookDuplicate(String provider, String status) {
        increment("flashsale.payment.webhook.duplicate",
                TAG_PROVIDER, normalize(provider),
                TAG_STATUS, normalize(status));
    }

    public void recordPaymentWebhookSuccess(String provider) {
        increment("flashsale.payment.webhook.success", TAG_PROVIDER, normalize(provider));
    }

    public void recordPaymentWebhookFailure(String provider, String reason) {
        increment("flashsale.payment.webhook.failure",
                TAG_PROVIDER, normalize(provider),
                TAG_REASON, normalize(reason));
    }

    public void recordRedisCompensationSuccess(String reason) {
        increment("flashsale.compensation.redis.success", TAG_REASON, normalize(reason));
    }

    public void recordRedisCompensationFailure(String reason) {
        increment("flashsale.compensation.redis.failure", TAG_REASON, normalize(reason));
    }

    public void recordRedisCompensationNoop(String reason) {
        increment("flashsale.compensation.redis.noop", TAG_REASON, normalize(reason));
    }

    private void increment(String name, String... tags) {
        meterRegistry.counter(name, tags).increment();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String normalized = value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (normalized.isBlank()) {
            return UNKNOWN;
        }
        if (normalized.length() > 64) {
            return normalized.substring(0, 64);
        }
        return normalized;
    }
}
