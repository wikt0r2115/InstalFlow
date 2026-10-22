package pl.instalflow.identity.security;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
class IdentitySecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        // PBKDF2 handles long passphrases without bcrypt's 72-byte truncation.
        return new DelegatingPasswordEncoder("pbkdf2", java.util.Map.of(
            "pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
            "bcrypt", new BCryptPasswordEncoder()));
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
        repository.setHeaderName("X-CSRF-TOKEN");
        return repository;
    }

    @Bean
    HttpSessionSecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    FilterRegistrationBean<CurrentUserFilter> currentUserFilterRegistration(CurrentUserFilter filter) {
        FilterRegistrationBean<CurrentUserFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false); // Only the Spring Security chain must run this filter.
        return registration;
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain identitySecurityFilterChain(HttpSecurity http,
            CurrentUserFilter currentUserFilter,
            CsrfTokenRepository csrfTokenRepository,
            HttpSessionSecurityContextRepository contextRepository,
            ObjectMapper objectMapper) throws Exception {
        http
            .securityContext(context -> context.securityContextRepository(contextRepository))
            .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/api/v1/auth/csrf", "/api/v1/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/openapi.yaml").hasRole("OWNER")
                .requestMatchers("/api/v1/auth/me", "/api/v1/auth/logout").authenticated()
                .requestMatchers("/api/v1/users/technicians").hasAnyRole("OWNER", "OFFICE")
                .requestMatchers("/api/v1/users", "/api/v1/users/**").hasRole("OWNER")
                .anyRequest().denyAll())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, error) -> problem(response, request, objectMapper,
                    401, "UNAUTHENTICATED", "Uwierzytelnienie jest wymagane"))
                .accessDeniedHandler((request, response, error) -> problem(response, request, objectMapper,
                    403, "FORBIDDEN", "Brak uprawnień do tej operacji")))
            .addFilterAfter(currentUserFilter, SecurityContextHolderFilter.class);
        return http.build();
    }

    static void problem(HttpServletResponse response, HttpServletRequest request,
            ObjectMapper mapper, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", HttpStatus.valueOf(status).getReasonPhrase());
        body.put("status", status);
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        body.put("code", code);
        body.put("requestId", request.getAttribute("requestId") == null ? "" : request.getAttribute("requestId"));
        mapper.writeValue(response.getOutputStream(), body);
    }
}
