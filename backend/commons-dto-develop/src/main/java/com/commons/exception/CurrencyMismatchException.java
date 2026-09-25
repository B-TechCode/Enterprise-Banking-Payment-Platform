package com.commons.exception;

/**
 * A request states one currency and the account it draws on holds another.
 *
 * <p>Distinct from a validation error because the request is well formed, and
 * distinct from a conflict because nothing changed underneath: the two facts
 * simply do not agree, and they will not agree on a retry. Mapped to 422 for
 * that reason - understood and refused, neither malformed nor transient.</p>
 */
public class CurrencyMismatchException extends RuntimeException {

    /**
     * Raised by the service that holds the account, which knows both currencies
     * and names them. Neither is a secret, and a caller told only "currency
     * mismatch" has to go and look up which account holds what before acting.
     */
    public CurrencyMismatchException(String requested, String held) {
        super("This request is in " + requested + " but the account is held in " + held);
    }

    /**
     * Raised by a service translating another service's 422, which does not
     * know the currencies because no downstream response body is forwarded.
     */
    public CurrencyMismatchException(String message) {
        super(message);
    }
}
