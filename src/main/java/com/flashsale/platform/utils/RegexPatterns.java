package com.flashsale.platform.utils;

public abstract class RegexPatterns {

    public static final String EMAIL_REGEX = "^[a-zA-Z0-9_-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$";
    public static final String PASSWORD_REGEX = "^\\w{4,32}$";
    public static final String VERIFY_CODE_REGEX = "^[a-zA-Z\\d]{6}$";

}
