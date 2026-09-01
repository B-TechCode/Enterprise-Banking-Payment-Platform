package com.commons.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class CurrentUser {

    public Optional<String> customerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }

        // Preferred: real customer identity claim
        Object customerId = jwt.getClaims().get("customer_id");
        if (customerId != null && !String.valueOf(customerId).isBlank()) {
            return Optional.of(String.valueOf(customerId));
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

        Object customerId = jwt.getClaims().get("customer_id");
        if (customerId != null && !String.valueOf(customerId).isBlank()) {
            return Optional.of(String.valueOf(customerId));
        }

        return Optional.empty();
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

