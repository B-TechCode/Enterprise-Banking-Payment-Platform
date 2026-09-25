package com.authuser.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.authuser.dto.CreateUserRequest;

/**
 * The initial password a customer is provisioned with.
 *
 * <p>Every customer used to be created with the literal "default-password",
 * identical across every account and committed to a public repository, so
 * knowing a customer's email was enough to sign in as them. These tests pin the
 * three properties that stops: the value differs every time, it satisfies the
 * connection policy without relying on chance, and no caller can supply or read
 * it.</p>
 */
class InitialPasswordGeneratorTest {

    private final InitialPasswordGenerator generator = new InitialPasswordGenerator();

    @Test
    @DisplayName("no two customers are provisioned with the same password")
    void passwordsAreNeverRepeated() {
        // The test that fails the moment anyone reintroduces a constant.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            seen.add(generator.generate());
        }
        assertThat(seen).hasSize(1_000);
    }

    @Test
    @DisplayName("every password satisfies an Auth0 database connection policy")
    void passwordsMeetComplexityPolicy() {
        // Checked over many draws rather than one, because a generator that
        // merely usually includes a symbol would create users that Auth0
        // rejects intermittently - the worst kind of failure to diagnose.
        for (int i = 0; i < 500; i++) {
            String password = generator.generate();

            assertThat(password).hasSize(32);
            assertThat(password).matches(".*[a-z].*");
            assertThat(password).matches(".*[A-Z].*");
            assertThat(password).matches(".*[0-9].*");
            assertThat(password).matches(".*[!@#$%^&*\\-_=+?].*");
        }
    }

    @Test
    @DisplayName("the class positions are shuffled, not fixed by construction")
    void classesAreNotAlwaysInTheSamePositions() {
        // The generator seeds one character per class before filling, so without
        // a shuffle the first four characters would always be lower, upper,
        // digit, symbol - a predictable shape is a smaller search space.
        Set<String> firstFour = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            firstFour.add(shape(generator.generate().substring(0, 4)));
        }
        assertThat(firstFour).hasSizeGreaterThan(1);
    }

    private static String shape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLowerCase(c))      b.append('l');
            else if (Character.isUpperCase(c)) b.append('u');
            else if (Character.isDigit(c))     b.append('d');
            else                               b.append('s');
        }
        return b.toString();
    }

    @Test
    @DisplayName("a caller cannot choose the password: the request carries no such field")
    void createUserRequestHasNoPasswordField() {
        // Reflection rather than a compile-time check, so that reintroducing the
        // field fails a test rather than quietly widening the API again.
        assertThat(fieldNames(CreateUserRequest.class))
                .containsExactlyInAnyOrder("email", "customerId");
    }

    private static Set<String> fieldNames(Class<?> type) {
        Set<String> names = new HashSet<>();
        for (Field f : type.getDeclaredFields()) {
            if (!f.isSynthetic()) {
                names.add(f.getName());
            }
        }
        return names;
    }
}
