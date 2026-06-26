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
// 认证过滤器
@Component
public class AuthFilter extends OncePerRequestFilter {

    private final StringRedisTemplate  stringRedisTemplate;

    public AuthFilter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {

        try {
            // 1.从请求头中获取token
            String token = request.getHeader("Authorization");

            // 2.没token 不做认证直接放行
            if (token == null || token.isBlank()) {
                filterChain.doFilter(request, response);
                return;
            }
            // 取token 去掉Bearer
            if (token.startsWith("Bearer ")) token = token.substring(7);

            // 3. 检验JWT有效性
            if(!JwtUtils.isValid(token)){
                filterChain.doFilter(request,response);
                return;
            }

            // 4. 取jti,用jti查redis
            String jti = JwtUtils.getJti(token);
            String key = RedisConstants.LOGIN_USER_KEY + jti;
            Map<Object,Object> userMap = stringRedisTemplate.opsForHash().entries(key);

            // 5.Redis 无会话 -> 放行
            if(userMap == null || userMap.isEmpty()){
                filterChain.doFilter(request,response);
                return;
            }

            // 6. 将获取的用户信息放到Dto,并刷新redisTTL
            UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(),false);
            stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

            // 6.1 从登陆态中取出角色
            String role = userDTO.getRole();

            // 6.2 准备一个 Spring Security 使用的权限集合
            List<GrantedAuthority> authorities = Collections.emptyList();

            // 6.3 如果当前role不为空,转成 Spring Security 能识别的权限对象
            if(role != null && !role.isBlank()){
                // SimpleGrantedAuthority 是 Spring Security 提供的、能够被框架直接识别的权限对象实现类。
                authorities = List.of(new SimpleGrantedAuthority(
                        "ROLE_" + role));
            }


            // 7. 将用户放入SecurityContext - （principal 就是 userDTO）
            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                    userDTO,
                    null,
                     authorities
                );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request,response);
        } finally {
            // 8. 清理线程
            SecurityContextHolder.clearContext();
        }
    }
}
