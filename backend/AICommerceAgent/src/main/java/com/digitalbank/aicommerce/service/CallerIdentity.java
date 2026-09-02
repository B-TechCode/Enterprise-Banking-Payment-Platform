package com.digitalbank.aicommerce.service;

import org.springframework.stereotype.Service;

import com.commons.exception.ForbiddenException;
import com.commons.security.CurrentUser;

import lombok.RequiredArgsConstructor;

/**
 * Resolves who is calling the agent, and refuses callers the agent must not act
 * for.
 *
 * <p>A client-credentials token is rejected outright. Such a token represents a
 * service rather than a person, so there is nobody who could confirm a payment,
 * and Account Service would grant it the service bypass on its ownership check.
 * The agent only ever acts as an identified human.</p>
 */
@Service
@RequiredArgsConstructor
public class CallerIdentity {

    private final CurrentUser currentUser;

    /** The customer this request is acting for, taken only from the token. */
    public String requireCustomerId() {

        if (currentUser.isClientCredentials()) {
            throw new ForbiddenException(
                    "The agent acts only for an authenticated user, not for a service token");
        }

        return currentUser.customerIdClaim()
                .orElseThrow(() -> new ForbiddenException(
                        "Token carries no customer identity"));
    }

    /** Auth0 subject, recorded in the audit trail. */
    public String subject() {
        return currentUser.customerId().orElse(null);
    }
}
