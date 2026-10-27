package dev.jeefox.smartmeal.auth.config;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
public class SecurityConfig {

    @Bean
    RequestProtectionFilter requestProtectionFilter(
            @org.springframework.beans.factory.annotation.Value(
                            "${smartmeal.security.auth-requests-per-minute:20}")
                    int authLimit,
            @org.springframework.beans.factory.annotation.Value(
                            "${smartmeal.security.writes-per-minute:120}")
                    int writeLimit) {
        return new RequestProtectionFilter(authLimit, writeLimit);
    }

    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<RequestProtectionFilter>
            protectionRegistration(RequestProtectionFilter filter) {
        var registration =
                new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, RequestProtectionFilter protection) throws Exception {

        http.authorizeHttpRequests(
                        auth ->
                                auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers(HttpMethod.POST, "/api/auth/register")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.GET, "/api/**")
                                        .authenticated()
                                        .requestMatchers(HttpMethod.HEAD, "/api/**")
                                        .authenticated()
                                        .requestMatchers("/api/me/**")
                                        .authenticated()
                                        .requestMatchers(
                                                "/api/products/**",
                                                "/api/recipes/**",
                                                "/api/store-products/**",
                                                "/api/stores/*/price-entry")
                                        .hasAnyRole("ADMIN", "CATALOG_EDITOR")
                                        .anyRequest()
                                        .denyAll())
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(
                        form ->
                                form.loginProcessingUrl("/api/auth/login")
                                        .usernameParameter("email")
                                        .passwordParameter("password")
                                        .successHandler(
                                                (request, response, authentication) ->
                                                        response.setStatus(
                                                                HttpStatus.NO_CONTENT.value()))
                                        .failureHandler(
                                                (request, response, exception) ->
                                                        response.setStatus(
                                                                HttpStatus.UNAUTHORIZED.value()))
                                        .permitAll())
                .logout(
                        logout ->
                                logout.logoutSuccessHandler(
                                        (request, response, authentication) ->
                                                response.setStatus(HttpStatus.NO_CONTENT.value())))
                .headers(
                        headers ->
                                headers.referrerPolicy(
                                                policy ->
                                                        policy.policy(
                                                                org.springframework.security.web
                                                                        .header.writers
                                                                        .ReferrerPolicyHeaderWriter
                                                                        .ReferrerPolicy
                                                                        .SAME_ORIGIN))
                                        .contentSecurityPolicy(
                                                csp ->
                                                        csp.policyDirectives(
                                                                "default-src 'self'; base-uri"
                                                                        + " 'self'; frame-ancestors"
                                                                        + " 'none'; object-src"
                                                                        + " 'none'")))
                .addFilterBefore(
                        protection,
                        org.springframework.security.web.authentication
                                .UsernamePasswordAuthenticationFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable);

        return http.build();
    }
}
