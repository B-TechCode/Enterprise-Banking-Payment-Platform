package com.commons.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for the identity signals that the account ownership check relies on.
 *
 * <p>AccountService.ensureOwnerOrAdmin admits a service-to-service caller only when
 * the token was issued through the client-credentials grant AND carries no
 * customer identity. Keying the bypass on a scope instead would also exempt
 * ordinary user tokens holding that scope, removing the ownership check for them.
 * These tests pin the two signals that make the grant-type check possible.</p>
 */
class CurrentUserTest {

    private final CurrentUser currentUser = new CurrentUser();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateWith(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @Test
    @DisplayName("customerIdClaim returns the customer_id claim when present")
    void customerIdClaimReturnsClaim() {
        authenticateWith(Map.of("sub", "auth0|user-1", "customer_id", "cust-123"));
        assertThat(currentUser.customerIdClaim()).contains("cust-123");
    }

    @Test
    @DisplayName("customerIdClaim is empty for a token with no customer_id, and does not fall back to sub")
    void customerIdClaimDoesNotFallBackToSub() {
        authenticateWith(Map.of("sub", "abc123@clients", "gty", "client-credentials"));

        // The sub-based fallback in customerId() is why an identity always looked
        // present; the strict accessor must report absence so ownership can be
        // decided correctly.
        assertThat(currentUser.customerIdClaim()).isEmpty();
        assertThat(currentUser.customerId()).contains("abc123@clients");
    }

    @Test
    @DisplayName("isClientCredentials detects the gty claim and the @clients subject form")
    void detectsClientCredentialsTokens() {
        authenticateWith(Map.of("sub", "abc123@clients", "gty", "client-credentials"));
        assertThat(currentUser.isClientCredentials()).isTrue();

        SecurityContextHolder.clearContext();
        authenticateWith(Map.of("sub", "xyz789@clients"));   // gty not emitted
        assertThat(currentUser.isClientCredentials()).isTrue();
    }

    @Test
    @DisplayName("a normal user token is not treated as client-credentials")
    void userTokenIsNotClientCredentials() {
        authenticateWith(Map.of("sub", "auth0|user-1", "customer_id", "cust-123"));

        assertThat(currentUser.isClientCredentials()).isFalse();
        // A user token therefore never reaches the service bypass, regardless of
        // which scopes it holds.
        assertThat(currentUser.customerIdClaim()).contains("cust-123");
    }

    @Test
    @DisplayName("no authentication yields no identity and no client-credentials signal")
    void unauthenticatedIsSafe() {
        SecurityContextHolder.clearContext();
        assertThat(currentUser.customerIdClaim()).isEmpty();
        assertThat(currentUser.isClientCredentials()).isFalse();
    }

    @Test
    @DisplayName("a non-JWT principal is not mistaken for a service token")
    void nonJwtPrincipalIsSafe() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone", "pw", List.of()));

        assertThat(currentUser.customerIdClaim()).isEmpty();
        assertThat(currentUser.isClientCredentials()).isFalse();
    }
}
