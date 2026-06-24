package com.flashsale.payment.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlashSaleOrderMessage {

    private Long orderId;

    private Long offerId;

    private Long userId;

    private LocalDateTime createdAt;
}
