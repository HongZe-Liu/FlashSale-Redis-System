package com.hmdp.utils;

// JWT 核心类 用于创建,解析,验证token

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

public class JwtUtils {

    // SECRET 用于签名JWT密钥
    private static final String SECRET = System.getenv("JWT_SECRET");

    // 将SECRET 转换为 SecretyKey 对象:JJWT 在签名和验证时需要 SECRETKEY 类型
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    // 设置Token 过期时间
    private static final long EXP_SECONDS = 60 * 60;

    public static Claims getClaims(String token){
        return parseAndValidate(token);
    }

    // 1. 生成token
        public static String generateToken(Long userId, String userName, String role){
            // 当前时间
            Instant now = Instant.now(); // 当前时间
            // 过期时间
            Instant exp = now.plusSeconds(EXP_SECONDS); // 过期时间
            // jti: 每个 JWT 的唯一 ID
            String jti = UUID.randomUUID().toString();
            // 构造Token函数
            return Jwts.builder()
                    // 标准字段:必须包含
                    .setSubject(userName) // sub:用户身份(用户名)
                    .setIssuedAt(Date.from(now)) // iat: 签发时间
                    .setExpiration(Date.from(exp)) // exp: 过期时间)
                    .setId(jti)
                    // 自定义字段:id,角色,权限...
                    .addClaims(Map.of(
                            "userId",userId,
                            "role",role
                    ))
                    // 算法签名
                    .signWith(KEY, SignatureAlgorithm.HS256)
                    // 最终生成
                    .compact();
        }

    // 2. 解析,验证token, 没问题返回claims,有问题则抛出异常
        // Claims = JWT 中的所有 payload 数据
        public static Claims parseAndValidate(String token) throws JwtException{
            return Jwts.parserBuilder()
                    .setSigningKey(KEY) // 设置签名密钥
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

    // 8. 读取gti
        public static String getJti(String token){
            Claims claims = getClaims(token);
            return claims.getId();
        }
}
