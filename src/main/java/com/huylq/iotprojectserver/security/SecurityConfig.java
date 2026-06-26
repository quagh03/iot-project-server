package com.huylq.iotprojectserver.security;

import tools.jackson.databind.ObjectMapper;
import com.huylq.iotprojectserver.common.denylist.DenylistJwtValidator;
import com.huylq.iotprojectserver.common.error.ErrorType;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationConverter;

import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtConfig jwtConfig;
    private static final String[] PUBLIC_ENDPOINTS = {
        "/api/v1/auth/login",
        "/api/v1/auth/refresh",
        "/api/v1/auth/logout",
        "/api/v1/oauth2/token",
        "/actuator/health",
        "/actuator/info",
        "/api/v1/api-docs/**",
        "/api/v1/swagger-ui.html",
        "/api/v1/swagger-ui/**",
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html"
    };

    @Bean
    PasswordEncoder passwordEncoder() {
        // Argon2id with parameters tuned by Spring Security 6 defaults.
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    RoleHierarchy roleHierarchy() {
        // Derives from Role declaration order — adding a role anywhere in the enum
        // automatically inserts it into the ladder. Each role implies the next so
        // a SUPER_ADMIN satisfies hasRole('VIEWER').
        Role[] ladder = Role.values();
        var builder = RoleHierarchyImpl.withDefaultRolePrefix();
        for (int i = 0; i < ladder.length - 1; i++) {
            builder = builder.role(ladder[i].name()).implies(ladder[i + 1].name());
        }
        return builder.build();
    }

    @Bean
    JwtDecoder jwtDecoder(DenylistJwtValidator denylistValidator) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefault();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(defaults, denylistValidator));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder() throws Exception {
        OctetSequenceKey jwk = new OctetSequenceKey.Builder(jwtConfig.secret().getBytes(StandardCharsets.UTF_8))
                .build();
        JWKSource<SecurityContext> source = new ImmutableJWKSet<>(new JWKSet(jwk));
        return new NimbusJwtEncoder(source);
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        scopes.setAuthorityPrefix("SCOPE_");
        scopes.setAuthoritiesClaimName("scope");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var authorities = new java.util.ArrayList<>(scopes.convert(jwt));
            String role = jwt.getClaimAsString("role");
            if (role != null) {
                authorities.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role));
            }
            return authorities;
        });
        converter.setPrincipalClaimName("sub");
        return converter;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper objectMapper,
                                    JwtAuthenticationConverter jwtAuthConverter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter)))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, res, e) -> writeProblem(res, objectMapper,
                                HttpServletResponse.SC_UNAUTHORIZED, ErrorType.UNAUTHENTICATED,
                                "Authentication required", req.getRequestURI()))
                        .accessDeniedHandler((req, res, e) -> writeProblem(res, objectMapper,
                                HttpServletResponse.SC_FORBIDDEN, ErrorType.FORBIDDEN,
                                "Insufficient role or scope", req.getRequestURI())))
                .headers(h -> h
                        .contentTypeOptions(c -> {})
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(63072000))
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'")));
        return http.build();
    }

    private SecretKeySpec secretKey() {
        byte[] keyBytes = jwtConfig.secret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("iot.security.jwt.secret must be at least 32 bytes (256 bits) for HS256");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    private static void writeProblem(HttpServletResponse res, ObjectMapper mapper, int status,
                                     ErrorType type, String detail, String path) throws java.io.IOException {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setType(type.uri());
        pd.setTitle(switch (status) {
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            default -> "Error";
        });
        pd.setDetail(detail);
        pd.setInstance(URI.create(path));
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.getWriter().write(mapper.writeValueAsString(pd));
    }
}
