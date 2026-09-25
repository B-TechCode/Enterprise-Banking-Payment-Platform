package com.digitalbank.customerservice.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What this service sends the identity provider when a customer is verified.
 *
 * <p>It used to send a password, the same literal for every customer. Choosing
 * a credential is not this service's concern and never was: AuthUser generates
 * one that nothing here sees. This test pins the absence, so reintroducing the
 * field fails rather than quietly restoring the old shape.</p>
 */
class CustomerRegistrationRequestTest {

    @Test
    @DisplayName("the registration request carries no password")
    void requestCarriesNoPassword() {
        Set<String> names = new HashSet<>();
        for (Field f : CustomerRegistrationRequest.class.getDeclaredFields()) {
            if (!f.isSynthetic()) {
                names.add(f.getName());
            }
        }

        assertThat(names).containsExactlyInAnyOrder("email", "customerId");
        assertThat(names).doesNotContain("password");
    }
}
