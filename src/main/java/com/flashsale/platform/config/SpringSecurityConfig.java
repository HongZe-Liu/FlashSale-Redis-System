package com.flashsale.platform.config;

import com.flashsale.platform.filter.AuthFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SpringSecurityConfig extends WebSecurityConfigurerAdapter {

    private final AuthFilter authFilter;
    private final Environment environment;

    public SpringSecurityConfig(AuthFilter authFilter, Environment environment) {
        this.authFilter = authFilter;
        this.environment = environment;
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http
                .csrf().disable()
                .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                .authorizeRequests()
                .antMatchers(
                        "/user/login",
                        "/swagger-ui.html", "/swagger-ui/**",
                        "/v2/api-docs", "/swagger-resources/**",
                        "/webjars/**", "/v3/api-docs/**",
                        "/user/code",
                        "/user/refresh",
                        "/payments/webhooks/mock"
                ).permitAll()
                .antMatchers(publicActuatorEndpoints()).permitAll()
                .anyRequest().authenticated()
                .and()
                .exceptionHandling()
                .authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"msg\":\"Authentication required\"}");
                })
                .accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(403);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"msg\":\"Access denied\"}");
                });
        http.addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class);
    }

    private String[] publicActuatorEndpoints() {
        if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
            return new String[]{
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info",
                    "/actuator/metrics",
                    "/actuator/metrics/**",
                    "/actuator/prometheus"
            };
        }
        return new String[]{
                "/actuator/health",
                "/actuator/health/**",
                "/actuator/prometheus"
        };
    }
}
