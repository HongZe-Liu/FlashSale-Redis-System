package com.flashsale.platform.filter;

import cn.hutool.core.bean.BeanUtil;
import com.flashsale.platform.dto.UserDTO;
import com.flashsale.platform.utils.JwtUtils;
import com.flashsale.platform.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@Component
public class AuthFilter extends OncePerRequestFilter {

    private final StringRedisTemplate stringRedisTemplate;

    public AuthFilter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        try {
            String token = request.getHeader("Authorization");

            if (token == null || token.isBlank()) {
                filterChain.doFilter(request, response);
                return;
            }
            if (token.startsWith("Bearer ")) {
                token = token.substring(7);
            }

            if (!JwtUtils.isValid(token)) {
                filterChain.doFilter(request, response);
                return;
            }

            String jti = JwtUtils.getJti(token);
            String key = RedisConstants.LOGIN_USER_KEY + jti;
            Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

            if (userMap == null || userMap.isEmpty()) {
                filterChain.doFilter(request, response);
                return;
            }

            UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
            stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

            String role = userDTO.getRole();
            List<GrantedAuthority> authorities = Collections.emptyList();

            if (role != null && !role.isBlank()) {
                authorities = List.of(new SimpleGrantedAuthority(
                        "ROLE_" + role));
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userDTO,
                            null,
                            authorities
                    );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
