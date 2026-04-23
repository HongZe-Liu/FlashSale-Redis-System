package com.hmdp.config;

import com.hmdp.filter.AuthFilter;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

// spring security 配置类
@Configuration
@EnableWebSecurity

public class springSecurityConfig extends WebSecurityConfigurerAdapter {

    // 注入,构造authRefreshFilter
    private final AuthFilter authFilter;

    public springSecurityConfig(AuthFilter authFilter) {
        this.authFilter = authFilter;
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception{
        http
                .csrf().disable() // 关闭csrf防护,使用jwt而不是session
                .sessionManagement()
                    .sessionCreationPolicy(SessionCreationPolicy.STATELESS) // 设置为不保存session,因为使用了jwt 无状态
                .and()
                .authorizeRequests() // 设置访问权限规则
                    .antMatchers(
                            "/user/login",
                            "/swagger-ui.html", "/swagger-ui/**",
                            "/v2/api-docs", "/swagger-resources/**",
                            "/webjars/**","/v3/api-docs/**",
                            "/v3/api-docs/**","/user/code",
                            "/user/refresh"

                    ).permitAll() // 这些接口无需登陆直接放行
                    .anyRequest().authenticated() // 除了指定的接口都需要验证
                .and()
                // 报错信息
                .exceptionHandling()
                .authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"msg\":\"未登陆\"}");
                })
                .accessDeniedHandler((req, res, ex) ->{
                    res.setStatus(403);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"msg\":\"无权限\"}");
                });
                // 添加AuthFilter到过滤链
        http.addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class);
    }
}