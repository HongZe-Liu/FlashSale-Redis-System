package com.flashsale.payment.utils;

// JWT 核心类 用于创建,解析,验证token

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

    // 将 secret 转换为 SecretKey 对象：JJWT 在签名和验证时需要 SecretKey 类型
    private static volatile SecretKey key;

    public JwtUtils(@Value("${jwt.secret}") String secret) {
        configureSecret(secret);
    }

    // 设置Token 过期时间
    private static final long EXP_SECONDS = 60 * 60;

    private static void configureSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("jwt.secret 不能为空");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("jwt.secret 长度至少需要 32 bytes");
        }
        key = Keys.hmacShaKeyFor(secretBytes);
    }

    private static SecretKey signingKey() {
        SecretKey current = key;
        if (current == null) {
            throw new IllegalStateException("JwtUtils 尚未初始化，请检查 jwt.secret 配置");
        }
        return current;
    }

    public static Claims getClaims(String token){
        return parseAndValidate(token);
    }

    // 1. 生成token
        public static String generateToken(Long userId, String userName, String role, String sid){
            // 当前时间
            Instant now = Instant.now(); // 当前时间
            // 过期时间
            Instant exp = now.plusSeconds(EXP_SECONDS); // 过期时间
            // jti: 每个 JWT 的唯一 ID
            String jti = UUID.randomUUID().toString();
            Map<String, Object> claims = new HashMap<>();
            claims.put("userId", userId);
            claims.put("role", role);
            claims.put("sid", sid);
            // 构造Token函数
            return Jwts.builder()
                    // 标准字段:必须包含
                    .setSubject(userName) // sub:用户身份(用户名)
                    .setIssuedAt(Date.from(now)) // iat: 签发时间
                    .setExpiration(Date.from(exp)) // exp: 过期时间)
                    .setId(jti)
                    // 自定义字段:id,角色,权限...
                    .addClaims(claims)
                    // 算法签名
                    .signWith(signingKey(), SignatureAlgorithm.HS256)
                    // 最终生成
                    .compact();
        }

    // 2. 解析,验证token, 没问题返回claims,有问题则抛出异常
        // Claims = JWT 中的所有 payload 数据
        public static Claims parseAndValidate(String token) throws JwtException{
            return Jwts.parserBuilder()
                    .setSigningKey(signingKey()) // 设置签名密钥
                    .build()
                    .parseClaimsJws(token) // 验证签名,过期时间
                    .getBody(); // 获取Payload(claims)
        }

    // 3. 判断token是否有效
        public static boolean isValid(String token) {
            try{
                parseAndValidate(token);
                return true;
            }catch(JwtException e){
                return false;
            }
        }

    // 4. 获取 userId
        public static Long getUserId(String token){
            Claims claims = JwtUtils.getClaims(token);

            /*
            * Claims 中的数字可能被解析为 Integer 或 Long
            * 因此先Object接,之后判断
            */
            Object v = claims.get("userId");
            return ((Number) v).longValue();
        }

    // 5. 获取 UserName
        public static String getUserName(String token){
            Claims claims = JwtUtils.getClaims(token);
            return claims.getSubject();
        }

    // 6. 获取角色
        public static String getRole(String token){
            Claims claims = JwtUtils.getClaims(token);
            Object v = claims.get("role");
            return v == null ? null : String.valueOf(v);
        }

    // 7. 获取过期时间
        public static Date getExpiration(String token){
            Claims claims = JwtUtils.getClaims(token);
            return claims.getExpiration();
        }

    // 8. 获取 sid
        public static String getSid(String token){
            Claims claims = getClaims(token);
            Object v = claims.get("sid");
            return v == null ? null : String.valueOf(v);
        }

    // 9. 读取gti
        public static String getJti(String token){
            Claims claims = getClaims(token);
            return claims.getId();
        }
}
