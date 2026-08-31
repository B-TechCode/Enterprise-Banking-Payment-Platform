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

    public boolean hasScope(String scope) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        return auth != null
                && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("SCOPE_" + scope));
    }
}

