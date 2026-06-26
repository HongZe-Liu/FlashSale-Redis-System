package com.flashsale.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.auth.refresh-cookie")
public class RefreshCookieProperties {
    private String name = "refresh_token";
    private boolean httpOnly = true;
    private boolean secure = true;
    private String sameSite = "Lax";
    private String path = "/user/refresh";
    private long maxAgeDays = 7L;
}
