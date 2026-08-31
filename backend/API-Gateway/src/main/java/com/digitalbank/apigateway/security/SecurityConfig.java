package com.digitalbank.apigateway.security;

import java.util.Collection;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuer;

    @Value("${auth0.audience:https://mockbank/api}")
    private String audience;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http) {

        JwtAuthenticationConverter converter =
                new JwtAuthenticationConverter();

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)

                .authorizeExchange(exchanges -> exchanges

                        // Public actuator endpoints
                        .pathMatchers("/actuator/**").permitAll()

                        // Public Swagger UI
                        .pathMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**"
                        ).permitAll()

                        // Public OpenAPI documentation
                        .pathMatchers(
                                "/v3/api-docs/**"
                        ).permitAll()

                        // Everything else requires Auth0 JWT
                        .anyExchange().authenticated()
                )

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtDecoder(jwtDecoder())
                                .jwtAuthenticationConverter(
                                        new ReactiveJwtAuthenticationConverterAdapter(
                                                converter
                                        )
                                )
                        )
                )

                .build();
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder() {

        NimbusReactiveJwtDecoder decoder =
                NimbusReactiveJwtDecoder
                        .withIssuerLocation(issuer)
                        .build();

        OAuth2TokenValidator<Jwt> withIssuer =
                JwtValidators.createDefaultWithIssuer(issuer);

        OAuth2TokenValidator<Jwt> withAudience = token -> {

            Object aud = token.getClaims().get("aud");

            if (aud instanceof String) {
                if (audience.equals(aud)) {
                    return OAuth2TokenValidatorResult.success();
                }
            }

            if (aud instanceof Collection<?>) {

                for (Object value : (Collection<?>) aud) {

                    if (audience.equals(String.valueOf(value))) {
                        return OAuth2TokenValidatorResult.success();
                    }
                }
            }

            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error(
                            "invalid_token",
                            "missing/invalid audience",
                            null
                    )
            );
        };

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        withIssuer,
                        withAudience
                )
        );

        return decoder;
    }
}
