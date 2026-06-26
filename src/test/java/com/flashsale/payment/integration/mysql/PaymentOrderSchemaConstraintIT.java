package com.flashsale.payment.integration.mysql;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentOrderSchemaConstraintIT extends AbstractMySqlIT {

    @Test
    void paymentOrder_enforcesOnePaymentOrderPerOrder() {
        insertPaymentOrder(9001L, 1001L, "MOCK", "mock_pay_9001");

        assertThatThrownBy(() -> insertPaymentOrder(9002L, 1001L, "MOCK", "mock_pay_9002"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void paymentOrder_enforcesProviderPaymentIdUniquenessPerProvider() {
        insertPaymentOrder(9001L, 1001L, "MOCK", "mock_pay_duplicate");

        assertThatThrownBy(() -> insertPaymentOrder(9002L, 1002L, "MOCK", "mock_pay_duplicate"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void paymentOrder_allowsSameProviderPaymentIdAcrossDifferentProviders() {
        insertPaymentOrder(9001L, 1001L, "MOCK", "shared_provider_payment_id");

        assertThatCode(() -> insertPaymentOrder(9002L, 1002L, "STRIPE", "shared_provider_payment_id"))
                .doesNotThrowAnyException();
    }

    private void insertPaymentOrder(Long id, Long orderId, String provider, String providerPaymentId) {
        jdbcTemplate.update(
                "INSERT INTO payment_order "
                        + "(id, order_id, user_id, provider, provider_payment_id, amount, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id,
                orderId,
                2L,
                provider,
                providerPaymentId,
                475L,
                "PENDING"
        );
    }
}
