package com.flashsale.platform.integration.mysql;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderSchemaConstraintIT extends AbstractMySqlIT {

    @Test
    void schemaScript_loadsSeedOffersAndUsers() {
        Integer userCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_user", Integer.class);
        Integer offerCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM offers", Integer.class);
        Integer flashSaleOfferCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM flash_sale_offers", Integer.class);

        assertThat(userCount).isGreaterThanOrEqualTo(4);
        assertThat(offerCount).isGreaterThanOrEqualTo(4);
        assertThat(flashSaleOfferCount).isGreaterThanOrEqualTo(4);
    }

    @Test
    void orders_enforcesOneOrderPerUserAndOffer() {
        insertOrder(10001L, 2L, 1L);

        assertThatThrownBy(() -> insertOrder(10002L, 2L, 1L))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void flashSaleStockConditionalUpdate_decrementsOnlyWhenStockIsPositive() {
        jdbcTemplate.update("UPDATE flash_sale_offers SET stock = 1 WHERE offer_id = 1");

        int firstUpdate = decrementStock(1L);
        int secondUpdate = decrementStock(1L);
        Integer stock = jdbcTemplate.queryForObject(
                "SELECT stock FROM flash_sale_offers WHERE offer_id = 1",
                Integer.class
        );

        assertThat(firstUpdate).isEqualTo(1);
        assertThat(secondUpdate).isZero();
        assertThat(stock).isZero();
    }

    private void insertOrder(Long id, Long userId, Long offerId) {
        jdbcTemplate.update(
                "INSERT INTO orders (id, user_id, offer_id, pay_amount) VALUES (?, ?, ?, ?)",
                id,
                userId,
                offerId,
                475L
        );
    }

    private int decrementStock(Long offerId) {
        return jdbcTemplate.update(
                "UPDATE flash_sale_offers SET stock = stock - 1 WHERE offer_id = ? AND stock > 0",
                offerId
        );
    }
}
