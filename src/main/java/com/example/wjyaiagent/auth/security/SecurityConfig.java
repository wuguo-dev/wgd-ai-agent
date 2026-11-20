package com.example.wjyaiagent.auth.security;

import com.example.wjyaiagent.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    HttpSessionSecurityContextRepository studentContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    HttpSessionCsrfTokenRepository studentCsrfRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    SecurityFilterChain studentSecurity(HttpSecurity http, UserRepository users, ObjectMapper mapper,
            HttpSessionSecurityContextRepository contextRepository,
            HttpSessionCsrfTokenRepository csrfRepository) throws Exception {
        // Servlet paths exclude the existing /api context path.
        // Existing ApiKeyFilter (+10) and RateLimitFilter (+20) run before Spring Security (-100).
        http.securityMatcher("/auth/**", "/community/**", "/admin/**", "/agent/**")
                .authorizeHttpRequests(rules -> rules
                        .requestMatchers("/auth/login", "/auth/register", "/auth/csrf").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/agent/**").hasAnyRole("STUDENT", "ADMIN")
                        .anyRequest().hasAnyRole("STUDENT", "ADMIN"))
                .securityContext(context -> context.securityContextRepository(contextRepository))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) ->
                                AuthErrorWriter.write(response, mapper, 401, "UNAUTHORIZED", "请先登录"))
                        .accessDeniedHandler((request, response, error) ->
                                AuthErrorWriter.write(response, mapper,
                                        org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication() == null ? 401 : 403,
                                        org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication() == null ? "UNAUTHORIZED" : "FORBIDDEN",
                                        "权限不足或安全校验失败")))
                .addFilterAfter(new StudentSessionFilter(users, mapper), SecurityContextHolderFilter.class);
        return http.build();
    }
}

