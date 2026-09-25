package com.payments.orch.client;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.commons.exception.ConflictException;
import com.commons.exception.ForbiddenException;
import com.commons.exception.CurrencyMismatchException;
import com.commons.exception.ResourceNotFoundException;
import com.commons.exception.UpstreamException;

import feign.Response;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns a refusal from another service into the same refusal from this one.
 *
 * <p>Feign's default decoder raises a FeignException, which nothing here
 * handles, so the shared exception handler's catch-all turned every downstream
 * answer into a 500. A customer paying from an account they do not own was told
 * the server had failed, when in truth their request had been refused, and the
 * same went for a missing account or insufficient funds.</p>
 *
 * <p>Each status is mapped to the exception the shared handler already
 * translates back into that status, so the answer the caller receives is the
 * one the downstream service gave.</p>
 *
 * <p>Only refusals a caller can act on are passed through. Anything else -
 * including a downstream 401, which means our relayed token was rejected rather
 * than the caller's being invalid - is an upstream failure and becomes a 502.</p>
 *
 * <p>No downstream response body is forwarded. These endpoints are reachable
 * from outside, and another service's error text can describe internals that a
 * caller has no business seeing, so each status carries a short message of its
 * own.</p>
 *
 * <p>Declared as a bean, so it applies to every Feign client in this service,
 * including the ones the Kafka consumers use. There a translated exception
 * behaves as the old one did: it propagates, the transaction rolls back, and
 * the event is redelivered.</p>
 */
@Configuration
@Slf4j
public class DownstreamStatusDecoder {

    /**
     * Named differently from this class on purpose: Spring registers a
     * configuration class under its own decapitalised name, so a bean method
     * called downstreamStatusDecoder would collide with it and the service
     * would not start.
     */
    @Bean
    public ErrorDecoder feignErrorDecoder() {
        return (methodKey, response) -> decode(methodKey, response);
    }

    private static RuntimeException decode(String methodKey, Response response) {

        log.warn("{} answered {} for {}", serviceOf(methodKey), response.status(), methodKey);

        return switch (response.status()) {
            case 403 -> new ForbiddenException(
                    "You may not use that account for this payment");
            case 404 -> new ResourceNotFoundException(
                    "The account for this payment was not found");
            case 409 -> new ConflictException(
                    "That account changed while this payment was being prepared; try again");
            // AccountService signals insufficient funds as a 400, never a
            // 422, so this case never carried that meaning. It now carries the
            // one thing AccountService does answer 422 for: an amount stated in
            // a currency the account is not held in.
            case 422 -> new CurrencyMismatchException(
                    "This payment is in a currency the account is not held in");
            default -> new UpstreamException(
                    "The payment could not be completed right now; please try again");
        };
    }

    /** The Feign interface a call was made through, for the log line only. */
    private static String serviceOf(String methodKey) {
        int dot = methodKey.indexOf('#');
        return dot > 0 ? methodKey.substring(0, dot) : methodKey;
    }
}
