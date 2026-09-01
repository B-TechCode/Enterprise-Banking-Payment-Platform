package com.commons.security;

import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Default security configuration for the microservices.
 *
 * <p>
 * This configuration turns the service into an OAuth2 Resource Server.
 * Every protected request must contain a valid Auth0 JWT access token.
 * </p>
 *
 * <p>
 * The JWT is validated for:
 * </p>
 *
 * <ul>
 *     <li>RS256 signature</li>
 *     <li>Issuer (iss)</li>
 *     <li>Audience (aud)</li>
 * </ul>
 *
 * <p>
 * The validated JWT is then converted into Spring Security authorities
 * using {@link JwtToAuthConverter}.
 * </p>
 */
@Configuration
@EnableMethodSecurity
public class DefaultSecurityConfig {

    /**
     * Auth0 issuer URL.
     *
     * Example:
     * https://your-tenant.us.auth0.com/
     */
    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuer;

    /**
     * Auth0 API audience.
     *
     * Example:
     * https://mockbank/api
     */
    @Value("${auth0.audience}")
    private String audience;

    /**
     * Converts validated JWT claims into Spring Security authorities.
     */
    private final JwtToAuthConverter jwtToAuthConverter;

    public DefaultSecurityConfig(JwtToAuthConverter jwtToAuthConverter) {
        this.jwtToAuthConverter = jwtToAuthConverter;
    }

    /**
     * Configure HTTP security.
     *
     * <p>
     * The application is stateless because authentication is performed
     * using JWT access tokens rather than HTTP sessions.
     * </p>
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http

            /*
             * CSRF protection is not required for this stateless REST API.
             */
            .csrf(csrf -> csrf.disable())

            /*
             * Do not create HTTP sessions.
             */
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            /*
             * Configure public and protected endpoints.
             */
            .authorizeHttpRequests(auth -> auth

                /*
                 * Public endpoints.
                 */
                .requestMatchers(
                    "/actuator/health",
                    "/actuator/info",

                    /*
                     * Metrics scraping is machine-to-machine and must not
                     * require a user JWT. Prometheus scrapes this endpoint
                     * directly over the internal network.
                     */
                    "/actuator/prometheus",

                    "/swagger-ui.html",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",

                    "/api/v1/health",
                    "/api/v1/customer/register",
                    "/.well-known/jwks.json",
                    "/api/v1/test/public",
                    "/api/v1/customers",
                    "/api/v1/customers/health"
                )
                .permitAll()

                /*
                 * Every other endpoint requires a valid JWT.
                 */
                .anyRequest()
                .authenticated()
            )

            /*
             * Enable OAuth2 Resource Server JWT authentication.
             */
            .oauth2ResourceServer(oauth2 ->
                oauth2
                    .jwt(jwt ->
                        jwt
                            /*
                             * Use our custom JWT decoder.
                             */
                            .decoder(jwtDecoder())

                            /*
                             * Convert scopes/permissions into
                             * Spring Security authorities.
                             */
                            .jwtAuthenticationConverter(jwtToAuthConverter)
                    )
            );

        return http.build();
    }

    /**
     * Creates the JWT decoder.
     *
     * <p>
     * Auth0's OpenID configuration is discovered from the issuer URL.
     * The decoder then downloads Auth0's JWKS public keys and verifies
     * the JWT signature.
     * </p>
     *
     * <p>
     * In addition to signature validation, we explicitly validate:
     * </p>
     *
     * <ul>
     *     <li>issuer</li>
     *     <li>audience</li>
     * </ul>
     */
    @Bean
    public JwtDecoder jwtDecoder() {

        /*
         * Build Nimbus JWT decoder using Auth0 issuer metadata.
         *
         * This automatically discovers the JWKS endpoint.
         */
        NimbusJwtDecoder decoder =
            (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);

        /*
         * Validator 1:
         *
         * Verify that:
         *
         * iss == configured Auth0 issuer
         */
        OAuth2TokenValidator<Jwt> withIssuer =
            JwtValidators.createDefaultWithIssuer(issuer);

        /*
         * Validator 2:
         *
         * Verify that the JWT contains our expected API audience.
         *
         * IMPORTANT:
         *
         * Auth0 can represent the "aud" claim as either:
         *
         * 1. a String
         *
         *       "https://mockbank/api"
         *
         * OR
         *
         * 2. a Collection/List
         *
         *       ["https://mockbank/api"]
         *
         * The previous implementation only accepted List.
         *
         * Your current token is being decoded with:
         *
         *       aud = https://mockbank/api
         *
         * Therefore the old validator rejected an otherwise valid token
         * and produced HTTP 401.
         */
        OAuth2TokenValidator<Jwt> withAudience = token -> {

            Object aud = token.getClaims().get("aud");

            /*
             * Case 1:
             *
             * JWT audience is represented as a String.
             */
            if (aud instanceof String) {

                String audienceValue = (String) aud;

                if (audience.equals(audienceValue)) {
                    return OAuth2TokenValidatorResult.success();
                }
            }

            /*
             * Case 2:
             *
             * JWT audience is represented as a Collection/List.
             */
            if (aud instanceof Collection<?>) {

                for (Object value : (Collection<?>) aud) {

                    if (audience.equals(String.valueOf(value))) {
                        return OAuth2TokenValidatorResult.success();
                    }
                }
            }

            /*
             * Audience was missing or did not contain
             * https://mockbank/api.
             */
            return OAuth2TokenValidatorResult.failure(
                new OAuth2Error(
                    "invalid_token",
                    "missing/invalid audience",
                    null
                )
            );
        };

        /*
         * Both validators must pass:
         *
         * issuer AND audience
         */
        decoder.setJwtValidator(
            new DelegatingOAuth2TokenValidator<>(
                withIssuer,
                withAudience
            )
        );

        return decoder;
    }
}