package com.flashsale.payment.enums;

public enum OrderStatus {

    PENDING_PAYMENT(1),
    PAID(2),
    CANCELLED(3),
    EXPIRED(4),
    REFUNDING(5),
    REFUNDED(6);

    private final int code;

    OrderStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static OrderStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (OrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}
