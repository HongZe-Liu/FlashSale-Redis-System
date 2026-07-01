package com.flashsale.platform.config;

import com.flashsale.platform.filter.AuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SpringSecurityConfig {

    private final AuthFilter authFilter;
    private final Environment environment;

    public SpringSecurityConfig(AuthFilter authFilter, Environment environment) {
        this.authFilter = authFilter;
        this.environment = environment;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/user/login",
                                "/swagger-ui.html", "/swagger-ui/**",
                                "/v2/api-docs", "/swagger-resources/**",
                                "/webjars/**", "/v3/api-docs/**",
                                "/user/code",
                                "/user/refresh",
                                "/payments/webhooks/mock"
                        ).permitAll()
                        .requestMatchers(publicActuatorEndpoints()).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((req, res, ex) -> {
                            res.setStatus(401);
                            res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write("{\"msg\":\"Authentication required\"}");
                        })
                        .accessDeniedHandler((req, res, ex) -> {
                            res.setStatus(403);
                            res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write("{\"msg\":\"Access denied\"}");
                        }));
        http.addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
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
