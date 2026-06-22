package com.flashsale.payment.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_CODE_LIMIT_KEY = "login:code:limit:"; // 限流
    public static final Long LOGIN_CODE_LIMIT_TTL = 60L;
    public static final String LOGIN_CODE_FAIL_KEY = "login:code:fail:";
    public static final Long LOGIN_CODE_FAIL_TTL = 1L;
    public static final Long LOGIN_CODE_MAX_RETRY = 5L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 60L;
    public static final String LOGIN_REFRESH_KEY = "login:refresh:";
    public static final String LOGIN_REFRESH_USED_KEY = "login:refresh:used:";
    public static final String LOGIN_SESSION_KEY = "login:session:";

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    public static final String SECKILL_VOUCHER_KEY = "seckill:voucher:";
    public static final Long SECKILL_CACHE_RETAIN_DAYS = 1L;
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final Long  REFRESH_TTL = 7L;
}
