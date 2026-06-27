package com.flashsale.platform.controller;


import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flashsale.platform.config.RefreshCookieProperties;
import com.flashsale.platform.dto.LoginFormDTO;
import com.flashsale.platform.dto.Result;
import com.flashsale.platform.dto.UserDTO;
import com.flashsale.platform.entity.User;
import com.flashsale.platform.entity.UserInfo;
import com.flashsale.platform.mapper.UserMapper;
import com.flashsale.platform.observability.BusinessMetrics;
import com.flashsale.platform.service.IUserInfoService;
import com.flashsale.platform.service.IUserService;
import com.flashsale.platform.utils.*;
import jakarta.mail.MessagingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.flashsale.platform.utils.RedisConstants.*;


@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    private static final String USER_STATUS_ACTIVE = "ACTIVE";

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private UserMapper userMapper;
    @Resource
    private RefreshCookieProperties refreshCookieProperties;
    @Resource
    private BusinessMetrics businessMetrics;

    @PostMapping("code")
    public Result sendCode(@RequestParam("email") String email, HttpSession session) {
        if (RegexUtils.isEmailInvalid(email)) {
            return Result.fail("Invalid email format");
        }

        String limitKey = LOGIN_CODE_LIMIT_KEY + email;
        Boolean allowed = stringRedisTemplate.opsForValue()
                .setIfAbsent(limitKey, "1", LOGIN_CODE_LIMIT_TTL, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(allowed)) {
            businessMetrics.recordCodeRateLimited();
            return Result.fail("Verification code requested too frequently");
        }

        String code = MailUtils.achieveCode();

        try {
            MailUtils.sendMail(email, code);
            stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + email, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
            businessMetrics.recordCodeSent();
            log.info("Verification code email sent, email={}", MaskUtils.maskEmail(email));
        } catch (MessagingException e) {
            stringRedisTemplate.delete(limitKey);
            log.error("Failed to send verification code email, email={}", MaskUtils.maskEmail(email), e);
            return Result.fail("Failed to send verification email");
        }
        return Result.ok("Verification code sent");
    }

    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session, HttpServletResponse response) {

        String email = loginForm.getEmail();
        String code = loginForm.getCode();

        if (RegexUtils.isEmailInvalid(email)) {
            log.warn("Invalid login email format, email={}", MaskUtils.maskEmail(email));
            businessMetrics.recordLoginFailure("invalid_email");
            return Result.fail("Invalid account");
        }

        String failKey = LOGIN_CODE_FAIL_KEY + email;
        String failCountStr = stringRedisTemplate.opsForValue().get(failKey);
        if (failCountStr != null && Long.parseLong(failCountStr) >= LOGIN_CODE_MAX_RETRY) {
            businessMetrics.recordLoginFailure("code_locked");
            return Result.fail("Too many invalid verification code attempts");
        }

        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + email);
        if (code == null || !code.equals(cacheCode)) {
            Long failCount = stringRedisTemplate.opsForValue().increment(failKey);
            if (Long.valueOf(1L).equals(failCount)) {
                stringRedisTemplate.expire(failKey, LOGIN_CODE_FAIL_TTL, TimeUnit.MINUTES);
            }
            if (failCount != null && failCount >= LOGIN_CODE_MAX_RETRY) {
                businessMetrics.recordLoginFailure("code_locked");
                return Result.fail("Too many invalid verification code attempts");
            }
            log.info("Verification code validation failed, email={}", MaskUtils.maskEmail(email));
            businessMetrics.recordLoginFailure("invalid_code");
            return Result.fail("Invalid verification code");
        }
        stringRedisTemplate.delete(failKey);

        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getEmail, email);
        User user = userService.getOne(queryWrapper);

        if (user == null) {
            log.info("Creating user for first login, email={}", MaskUtils.maskEmail(email));
            user = userService.createUserByEmail(email);
        }
        if (!USER_STATUS_ACTIVE.equals(user.getStatus())) {
            businessMetrics.recordLoginFailure("user_disabled");
            return Result.fail("Account is disabled");
        }

        String role = user.getRole() == null ? "USER" : user.getRole();
        String sid = UUID.randomUUID().toString();
        String token = JwtUtils.generateToken(user.getId(), user.getNickName(), role, sid);
        String jti  = JwtUtils.getJti(token);
        String refreshToken = RefreshTokenUtils.generateRefreshToken();

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        String icon = userDTO.getIcon() == null ? "" : userDTO.getIcon();
        String nickName = userDTO.getNickName() == null ? "" : userDTO.getNickName();
        String userRole = userDTO.getRole() == null ? "USER" : userDTO.getRole();

        HashMap<String, String> userMap = new HashMap<>();
        userMap.put("id", user.getId().toString());
        userMap.put("nickName", nickName);
        userMap.put("icon", icon);
        userMap.put("role", userRole);

        String tokenKey = LOGIN_USER_KEY + jti;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, 60, TimeUnit.MINUTES);
        stringRedisTemplate.opsForValue().set(
                LOGIN_REFRESH_KEY + refreshToken, sid,
                REFRESH_TTL,
                TimeUnit.DAYS
        );
        writeSessionState(sid, user.getId(), refreshToken, jti);
        ResponseCookie cookie = buildRefreshCookie(refreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        stringRedisTemplate.delete(LOGIN_CODE_KEY + email);
        businessMetrics.recordLoginSuccess();
        return Result.ok(token);
    }

    @PostMapping("/logout")
    public Result logout(HttpServletRequest request,
                         @CookieValue(value = "${app.auth.refresh-cookie.name:refresh_token}", required = false) String refreshToken,
                         HttpServletResponse response) {
        String token = request.getHeader("Authorization");
        String sid = null;
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
            if (JwtUtils.isValid(token)) {
                String jti = JwtUtils.getJti(token);
                sid = JwtUtils.getSid(token);
                stringRedisTemplate.delete(LOGIN_USER_KEY + jti);
            }
        }

        if (refreshToken != null && !refreshToken.isBlank()) {
            String refreshSid = stringRedisTemplate.opsForValue().get(LOGIN_REFRESH_KEY + refreshToken);
            if (refreshSid != null && !refreshSid.isBlank()) {
                sid = refreshSid;
            } else {
                stringRedisTemplate.delete(LOGIN_REFRESH_KEY + refreshToken);
                stringRedisTemplate.delete(LOGIN_REFRESH_USED_KEY + refreshToken);
            }
        }

        if (sid != null && !sid.isBlank()) {
            invalidateSession(sid);
        }

        ResponseCookie cookie = buildRefreshCookie("", Duration.ZERO);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return Result.ok("Logged out");
    }

    @GetMapping("/me")
    public Result me(@AuthenticationPrincipal UserDTO user) {
        return Result.ok(user);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId) {
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        return Result.ok(info);
    }

    @PostMapping("/refresh")
    public Result refresh(@CookieValue(value = "${app.auth.refresh-cookie.name:refresh_token}", required = false)
                                   String refreshToken,
                                   HttpServletResponse response) {

        if (refreshToken == null || refreshToken.isBlank()) {
            auditRefreshFailure("missing_refresh_cookie", null, null, null);
            return Result.fail("User is not authenticated");
        }

        String refreshKey = LOGIN_REFRESH_KEY + refreshToken;
        String sid = stringRedisTemplate.opsForValue().get(refreshKey);
        if (sid == null || sid.isBlank()) {
            String usedSid = stringRedisTemplate.opsForValue().get(LOGIN_REFRESH_USED_KEY + refreshToken);
            if (usedSid != null && !usedSid.isBlank()) {
                auditRefreshFailure("refresh_token_reuse_detected", usedSid, null, refreshToken);
                invalidateSession(usedSid);
                return Result.fail("Invalid session state; please log in again");
            }
            auditRefreshFailure("refresh_token_not_found", null, null, refreshToken);
            return Result.fail("User session does not exist");
        }

        String sessionKey = LOGIN_SESSION_KEY + sid;
        Map<Object, Object> sessionMap = stringRedisTemplate.opsForHash().entries(sessionKey);
        if (sessionMap == null || sessionMap.isEmpty()) {
            auditRefreshFailure("session_not_found", sid, null, refreshToken);
            stringRedisTemplate.delete(refreshKey);
            return Result.fail("Session expired; please log in again");
        }

        String currentRefreshToken = valueAsString(sessionMap.get("currentRefreshToken"));
        String currentAccessJti = valueAsString(sessionMap.get("currentAccessJti"));
        String userIdStr = valueAsString(sessionMap.get("userId"));
        if (userIdStr == null || userIdStr.isBlank()) {
            auditRefreshFailure("session_user_missing", sid, null, refreshToken);
            invalidateSession(sid);
            return Result.fail("Session expired; please log in again");
        }
        if (!refreshToken.equals(currentRefreshToken)) {
            auditRefreshFailure("refresh_token_session_mismatch", sid, userIdStr, refreshToken);
            invalidateSession(sid);
            return Result.fail("Invalid session state; please log in again");
        }

        Long userId = Long.valueOf(userIdStr);
        User user = userService.getById(userId);
        if (user == null) {
            auditRefreshFailure("user_not_found", sid, userIdStr, refreshToken);
            invalidateSession(sid);
            return Result.fail("User does not exist; please log in again");
        }
        if (!USER_STATUS_ACTIVE.equals(user.getStatus())) {
            auditRefreshFailure("user_disabled", sid, userIdStr, refreshToken);
            invalidateSession(sid);
            return Result.fail("Account is disabled");
        }
        String role = user.getRole() == null ? "USER" : user.getRole();
        String newAccessToken = JwtUtils.generateToken(user.getId(), user.getNickName(), role, sid);
        String newRefreshToken = RefreshTokenUtils.generateRefreshToken();

        String accessJti = JwtUtils.getJti(newAccessToken);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        String icon = userDTO.getIcon() == null ? "" : userDTO.getIcon();
        String nickName = userDTO.getNickName() == null ? "" : userDTO.getNickName();
        String userRole = userDTO.getRole() == null ? "USER" : userDTO.getRole();

        Map<String, String> userMap = new HashMap<>();
        userMap.put("id", user.getId().toString());
        userMap.put("nickName", nickName);
        userMap.put("icon", icon);
        userMap.put("role", userRole);
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + accessJti, userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY + accessJti, LOGIN_USER_TTL, TimeUnit.MINUTES);
        if (currentAccessJti != null && !currentAccessJti.isBlank()) {
            stringRedisTemplate.delete(LOGIN_USER_KEY + currentAccessJti);
        }
        stringRedisTemplate.delete(refreshKey);
        stringRedisTemplate.opsForValue().set(LOGIN_REFRESH_USED_KEY + refreshToken, sid, REFRESH_TTL, TimeUnit.DAYS);
        stringRedisTemplate.opsForValue().set(LOGIN_REFRESH_KEY + newRefreshToken, sid, REFRESH_TTL, TimeUnit.DAYS);
        writeSessionState(sid, userId, newRefreshToken, accessJti);

        ResponseCookie cookie = buildRefreshCookie(newRefreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return Result.ok(newAccessToken);
    }

    private void writeSessionState(String sid, Long userId, String refreshToken, String accessJti) {
        Map<String, String> sessionMap = new HashMap<>();
        sessionMap.put("userId", String.valueOf(userId));
        sessionMap.put("currentRefreshToken", refreshToken);
        sessionMap.put("currentAccessJti", accessJti);
        stringRedisTemplate.opsForHash().putAll(LOGIN_SESSION_KEY + sid, sessionMap);
        stringRedisTemplate.expire(LOGIN_SESSION_KEY + sid, REFRESH_TTL, TimeUnit.DAYS);
    }

    private ResponseCookie buildRefreshCookie(String value) {
        return buildRefreshCookie(value, Duration.ofDays(refreshCookieProperties.getMaxAgeDays()));
    }

    private ResponseCookie buildRefreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(refreshCookieProperties.getName(), value)
                .httpOnly(refreshCookieProperties.isHttpOnly())
                .secure(refreshCookieProperties.isSecure())
                .sameSite(refreshCookieProperties.getSameSite())
                .path(refreshCookieProperties.getPath())
                .maxAge(maxAge)
                .build();
    }

    private void invalidateSession(String sid) {
        String sessionKey = LOGIN_SESSION_KEY + sid;
        Map<Object, Object> sessionMap = stringRedisTemplate.opsForHash().entries(sessionKey);
        if (sessionMap != null && !sessionMap.isEmpty()) {
            String currentAccessJti = valueAsString(sessionMap.get("currentAccessJti"));
            String currentRefreshToken = valueAsString(sessionMap.get("currentRefreshToken"));
            if (currentAccessJti != null && !currentAccessJti.isBlank()) {
                stringRedisTemplate.delete(LOGIN_USER_KEY + currentAccessJti);
            }
            if (currentRefreshToken != null && !currentRefreshToken.isBlank()) {
                stringRedisTemplate.delete(LOGIN_REFRESH_KEY + currentRefreshToken);
            }
        }
        stringRedisTemplate.delete(sessionKey);
    }

    private void auditRefreshFailure(String reason, String sid, String userId, String refreshToken) {
        log.warn("Refresh token rejected, reason={}, sid={}, userId={}, refreshToken={}",
                reason,
                sid,
                userId,
                maskToken(refreshToken));
    }

    private String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return "null";
        }
        int prefix = Math.min(8, token.length());
        return token.substring(0, prefix) + "***";
    }

    private String valueAsString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
