package com.flashsale.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("payment_webhook_event")
public class PaymentWebhookEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String provider;

    private String eventId;

    private String eventType;

    private String providerPaymentId;

    private Long orderId;

    private String status;

    private String rawPayload;

    private String errorMessage;

    private LocalDateTime processedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
