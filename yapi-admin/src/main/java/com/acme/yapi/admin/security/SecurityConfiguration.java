package com.acme.yapi.admin.security;

import java.time.Clock;
import java.util.ArrayList;
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
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.acme.yapi.admin.config.AdminProperties;
import com.acme.yapi.admin.environment.EnvironmentClient;
import com.acme.yapi.admin.environment.EnvironmentRepository;
import com.acme.yapi.admin.web.Problems;

/**
 * Every {@code /api/v1} call carries the session of the company's login application ({@code X-Token}, {@code X-Uid},
 * {@code X-Clientip}, {@code X-User-Agent}), checked through the session environment's Admin API; roles come from the
 * session's Yapi permissions ({@code yapi.admin.session.roles}). In the {@code local} profile HTTP Basic replaces it
 * with the users {@code viewer}, {@code editor}, {@code approver} and {@code lead} (password = user name; {@code lead}
 * has all three roles).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    private static final AccessDeniedHandler FORBIDDEN = (request, response, exception) ->
            Problems.write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Bu işlem için rolünüz yetmiyor.");

    /** 401 with the login page, so the screen can send the user there. */
    private static AuthenticationEntryPoint unauthenticated(LoginUrl loginUrl) {
        return (request, response, exception) -> Problems.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Oturum yok ya da geçersiz.", loginUrl.properties());
    }

    private static HttpSecurity common(HttpSecurity http, LoginUrl loginUrl) throws Exception {
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
                        .authenticationEntryPoint(unauthenticated(loginUrl))
                        .accessDeniedHandler(FORBIDDEN));
    }

    @Bean
    @Profile("!local")
    SessionVerifier sessionVerifier(EnvironmentClient client, EnvironmentRepository environments,
                                    AdminProperties properties) {
        return new SessionVerifier(client, environments, properties, Clock.systemUTC());
    }

    @Bean
    @Profile("!local")
    SecurityFilterChain session(HttpSecurity http, SessionVerifier verifier, AdminProperties properties,
                                LoginUrl loginUrl) throws Exception {
        return common(http, loginUrl)
                .addFilterBefore(new SessionAuthenticationFilter(verifier, properties.session()),
                        AnonymousAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Profile("local")
    SecurityFilterChain local(HttpSecurity http, LoginUrl loginUrl) throws Exception {
        return common(http, loginUrl)
                .httpBasic(basic -> basic.authenticationEntryPoint(unauthenticated(loginUrl)))
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
        List<String> headers = new ArrayList<>(List.of("Authorization", "Content-Type"));
        headers.addAll(SessionAuthenticationFilter.HEADERS);
        cors.setAllowedHeaders(headers);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
