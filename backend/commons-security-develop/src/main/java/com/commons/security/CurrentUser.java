package com.commons.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class CurrentUser {

    /**
     * Namespaced customer identity claim.
     *
     * <p>Auth0 requires custom claims to be namespaced with a URI and silently
     * drops unnamespaced ones, so an Action that sets the customer identity
     * during login must emit it under this name. Checked first.</p>
     */
    static final String NAMESPACED_CUSTOMER_ID_CLAIM = "https://mockbank/customer_id";

    /**
     * Unnamespaced fallback, retained for tokens minted outside the Auth0 login
     * flow (tests and existing service-to-service callers).
     */
    static final String CUSTOMER_ID_CLAIM = "customer_id";

    /**
     * Reads the customer identity from whichever claim carries it, preferring
     * the namespaced form that Auth0 emits.
     */
    private static Optional<String> readCustomerId(Jwt jwt) {
        for (String claim : new String[] { NAMESPACED_CUSTOMER_ID_CLAIM, CUSTOMER_ID_CLAIM }) {
            Object value = jwt.getClaims().get(claim);
            if (value != null && !String.valueOf(value).isBlank()) {
                return Optional.of(String.valueOf(value));
            }
        }
        return Optional.empty();
    }

    public Optional<String> customerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }

        // Preferred: real customer identity claim, namespaced or bare.
        Optional<String> customerId = readCustomerId(jwt);
        if (customerId.isPresent()) {
            return customerId;
        }

        // Auth0 client-credentials tokens do not normally contain customer_id.
        // Fall back to the authenticated subject/client identity.
        Object subject = jwt.getClaims().get("sub");
        if (subject != null && !String.valueOf(subject).isBlank()) {
            return Optional.of(String.valueOf(subject));
        }

        return Optional.empty();
    }

    /**
     * The caller's customer identity as asserted by the {@code customer_id} claim.
     *
     * <p>Unlike {@link #customerId()} this does <b>not</b> fall back to {@code sub}.
     * A missing value therefore means "this token carries no customer identity",
     * which is exactly the condition an ownership check must be able to detect.</p>
     */
    public Optional<String> customerIdClaim() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }

        return readCustomerId(jwt);
    }

    /**
     * Whether the current token was issued through the OAuth2 client-credentials
     * grant, i.e. it represents a service rather than an end user.
     *
     * <p>Auth0 marks these tokens with {@code gty=client-credentials} and issues
     * them a {@code sub} of the form {@code <client-id>@clients}. Either signal is
     * accepted, because {@code gty} is only present when Auth0 is configured to
     * include it.</p>
     */
    public boolean isClientCredentials() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return false;
        }

        Object grantType = jwt.getClaims().get("gty");
        if (grantType != null && "client-credentials".equals(String.valueOf(grantType))) {
            return true;
        }

        Object subject = jwt.getClaims().get("sub");
        return subject != null && String.valueOf(subject).endsWith("@clients");
    }

    public boolean hasScope(String scope) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        return auth != null
                && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("SCOPE_" + scope));
    }
}

