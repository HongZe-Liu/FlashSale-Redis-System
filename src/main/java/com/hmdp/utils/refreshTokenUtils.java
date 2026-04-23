package com.hmdp.utils;
// 用于生成refresh token

import java.util.UUID;

public class refreshTokenUtils {
    public static String generateRftoken(){
        return UUID.randomUUID().toString();
    }
}
