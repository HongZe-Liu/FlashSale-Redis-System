package com.flashsale.platform.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlashSaleOrderDeadLetterMessage {

    private Long orderId;

    private Long offerId;

    private Long userId;

    private String reason;

    private Integer retryCount;

    private String originalMessageBody;

    private LocalDateTime failedAt;
}
