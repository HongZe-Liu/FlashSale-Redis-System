package com.flashsale.payment.entity;

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
@TableName("payment_order")
public class PaymentOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long orderId;

    private Long userId;

    private String provider;

    private String providerPaymentId;

    private Long amount;

    private String currency;

    private String status;

    private String checkoutUrl;

    private LocalDateTime expiresAt;

    private LocalDateTime paidAt;

    private String failureReason;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
