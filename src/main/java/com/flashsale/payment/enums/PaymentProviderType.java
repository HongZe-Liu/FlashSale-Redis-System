package com.flashsale.payment.enums;

public enum PaymentProviderType {

    MOCK(1),
    STRIPE(2);

    private final int code;

    PaymentProviderType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static PaymentProviderType fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (PaymentProviderType providerType : values()) {
            if (providerType.name().equalsIgnoreCase(name.trim())) {
                return providerType;
            }
        }
        return null;
    }
}
