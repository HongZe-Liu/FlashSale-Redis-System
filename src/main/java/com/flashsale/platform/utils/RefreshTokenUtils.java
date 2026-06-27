package com.flashsale.platform.utils;

import java.util.UUID;

public final class RefreshTokenUtils {

    private RefreshTokenUtils() {
    }

    public static String generateRefreshToken() {
        return UUID.randomUUID().toString();
    }
}
