package com.authuser.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Produces the password an Auth0 database user is created with.
 *
 * <p>Auth0 requires a database-connection user to have a password, but nothing
 * here needs to know it. The value is generated, sent once, and forgotten: it is
 * never logged, never returned to a caller and never held in a field. A user
 * provisioned this way therefore has no usable password until one is set through
 * a password-change ticket - see backlog item 15.</p>
 *
 * <p>Before this, every customer was created with the literal
 * {@code "default-password"}, shared across every customer the platform had ever
 * verified and committed to a public repository. Knowing a customer's email was
 * enough to sign in as them.</p>
 *
 * <p>The character set deliberately excludes characters that are easy to confuse
 * when a value is read aloud or transcribed, which matters only if one ever has
 * to be handled by a person during recovery. It keeps one character from each of
 * four classes so the result satisfies an Auth0 connection policy up to
 * "Fair" without depending on chance.</p>
 */
@Component
public class InitialPasswordGenerator {

    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";   // no l
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";    // no I, O
    private static final String DIGIT = "23456789";                    // no 0, 1
    private static final String SYMBOL = "!@#$%^&*-_=+?";

    private static final String ALL = LOWER + UPPER + DIGIT + SYMBOL;

    /** Comfortably above any Auth0 policy minimum, and not a round number. */
    private static final int LENGTH = 32;

    private final SecureRandom random = new SecureRandom();

    /**
     * A fresh password. Never the same twice, and never recorded anywhere.
     */
    public String generate() {

        List<Character> chars = new ArrayList<>(LENGTH);

        // One from each class first, so the result cannot fail a complexity
        // policy because the draw happened to miss a class.
        chars.add(pick(LOWER));
        chars.add(pick(UPPER));
        chars.add(pick(DIGIT));
        chars.add(pick(SYMBOL));

        while (chars.size() < LENGTH) {
            chars.add(pick(ALL));
        }

        // Otherwise the first four positions would always hold the same classes
        // in the same order.
        Collections.shuffle(chars, random);

        StringBuilder password = new StringBuilder(LENGTH);
        chars.forEach(password::append);
        return password.toString();
    }

    private char pick(String alphabet) {
        return alphabet.charAt(random.nextInt(alphabet.length()));
    }
}
