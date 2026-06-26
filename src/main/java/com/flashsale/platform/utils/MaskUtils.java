package com.flashsale.platform.utils;
// 脱敏工具类
public class MaskUtils {

    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int atIndex = email.indexOf('@');
        if (atIndex == -1) {
            return "***";
        }
        return "***" + email.substring(atIndex);
    }

    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    public static String maskToken(String token) {
        if (token == null || token.length() < 10) {
            return "***";
        }
        return token.substring(0, 6) + "..." + token.substring(token.length() - 4);
    }

    public static String maskCode(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        return "***";
    }
}
