package com.flashsale.payment.integration.mysql;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentWebhookSchemaConstraintIT extends AbstractMySqlIT {

    @Test
    void paymentWebhookEvent_enforcesProviderEventIdUniqueness() {
        insertWebhookEvent(7001L, "MOCK", "evt_1");

        assertThatThrownBy(() -> insertWebhookEvent(7002L, "MOCK", "evt_1"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void paymentWebhookEvent_allowsSameEventIdAcrossDifferentProviders() {
        insertWebhookEvent(7001L, "MOCK", "evt_shared");

        assertThatCode(() -> insertWebhookEvent(7002L, "STRIPE", "evt_shared"))
                .doesNotThrowAnyException();
    }

    private void insertWebhookEvent(Long id, String provider, String eventId) {
        jdbcTemplate.update(
                "INSERT INTO payment_webhook_event "
                        + "(id, provider, event_id, event_type, provider_payment_id, order_id, status, raw_payload) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                provider,
                eventId,
                "payment.succeeded",
                "provider_payment_" + id,
                1001L,
                "PROCESSING",
                "{\"eventId\":\"" + eventId + "\"}"
        );
    }
}
