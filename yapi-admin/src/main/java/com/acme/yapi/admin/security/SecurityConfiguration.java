package com.acme.yapi.admin.security;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User.UserBuilder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.acme.yapi.admin.config.AdminProperties;
import com.acme.yapi.admin.web.Problems;

/**
 * Every {@code /api/v1} call carries the SSO access token ({@code Authorization: Bearer}). In the {@code local}
 * profile HTTP Basic replaces SSO with the users {@code viewer}, {@code editor}, {@code approver} and {@code lead}
 * (password = user name; {@code lead} has all three roles).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    private static final AuthenticationEntryPoint UNAUTHENTICATED = (request, response, exception) ->
            Problems.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Token yok ya da geçersiz.");
    private static final AccessDeniedHandler FORBIDDEN = (request, response, exception) ->
            Problems.write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Bu işlem için rolünüz yetmiyor.");

    private static HttpSecurity common(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(UNAUTHENTICATED)
                        .accessDeniedHandler(FORBIDDEN));
    }

    @Bean
    @Profile("!local")
    SecurityFilterChain sso(HttpSecurity http, AdminProperties properties) throws Exception {
        return common(http)
                .oauth2ResourceServer(server -> server
                        .authenticationEntryPoint(UNAUTHENTICATED)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new JwtRoles(properties.security()))))
                .build();
    }

    @Bean
    @Profile("local")
    SecurityFilterChain local(HttpSecurity http) throws Exception {
        return common(http)
                .httpBasic(basic -> basic.authenticationEntryPoint(UNAUTHENTICATED))
                .build();
    }

    @Bean
    @Profile("local")
    UserDetailsService localUsers() {
        return new InMemoryUserDetailsManager(
                user("viewer", Role.VIEWER),
                user("editor", Role.ROUTE_EDITOR),
                user("approver", Role.APPROVER),
                user("lead", Role.VIEWER, Role.ROUTE_EDITOR, Role.APPROVER));
    }

    private static org.springframework.security.core.userdetails.UserDetails user(String name, Role... roles) {
        UserBuilder builder = org.springframework.security.core.userdetails.User.withUsername(name)
                .password("{noop}" + name);
        return builder.roles(java.util.Arrays.stream(roles).map(Role::name).toArray(String[]::new)).build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AdminProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.cors().allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
