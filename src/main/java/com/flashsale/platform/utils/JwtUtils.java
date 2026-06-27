package com.flashsale.platform.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class JwtUtils {

    private static volatile SecretKey key;
    private static final long EXP_SECONDS = 60 * 60;

    public JwtUtils(@Value("${jwt.secret}") String secret) {
        configureSecret(secret);
    }

    private static void configureSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("jwt.secret must not be blank");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("jwt.secret must be at least 32 bytes");
        }
        key = Keys.hmacShaKeyFor(secretBytes);
    }

    private static SecretKey signingKey() {
        SecretKey current = key;
        if (current == null) {
            throw new IllegalStateException("JwtUtils is not initialized; check jwt.secret configuration");
        }
        return current;
    }

    public static Claims getClaims(String token) {
        return parseAndValidate(token);
    }

    public static String generateToken(Long userId, String userName, String role, String sid) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(EXP_SECONDS);
        String jti = UUID.randomUUID().toString();
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("role", role);
        claims.put("sid", sid);
        return Jwts.builder()
                .setSubject(userName)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(exp))
                .setId(jti)
                .addClaims(claims)
                .signWith(signingKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public static Claims parseAndValidate(String token) throws JwtException {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public static boolean isValid(String token) {
        try {
            parseAndValidate(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }

    public static Long getUserId(String token) {
        Claims claims = JwtUtils.getClaims(token);
        Object value = claims.get("userId");
        return ((Number) value).longValue();
    }

    public static String getUserName(String token) {
        Claims claims = JwtUtils.getClaims(token);
        return claims.getSubject();
    }

    public static String getRole(String token) {
        Claims claims = JwtUtils.getClaims(token);
        Object value = claims.get("role");
        return value == null ? null : String.valueOf(value);
    }

    public static Date getExpiration(String token) {
        Claims claims = JwtUtils.getClaims(token);
        return claims.getExpiration();
    }

    public static String getSid(String token) {
        Claims claims = getClaims(token);
        Object value = claims.get("sid");
        return value == null ? null : String.valueOf(value);
    }

    public static String getJti(String token) {
        Claims claims = getClaims(token);
        return claims.getId();
    }
}
