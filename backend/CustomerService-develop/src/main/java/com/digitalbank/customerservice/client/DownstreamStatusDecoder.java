package com.digitalbank.customerservice.client;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.commons.exception.ConflictException;
import com.commons.exception.ForbiddenException;
import com.commons.exception.ResourceNotFoundException;
import com.commons.exception.UpstreamException;

import feign.Response;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns a refusal from AuthUser into the same refusal from this service.
 *
 * <p>Feign's default decoder raises a FeignException, which nothing here
 * handles, so the shared exception handler's catch-all turned every answer from
 * AuthUser into a 500. An operator marking a customer verified was told the
 * server had failed, when in truth the registration had been refused - and a
 * 500 reads the same as an outage, so there was nothing to act on.</p>
 *
 * <p>Each status is mapped to the exception the shared handler already
 * translates back into that status, so the answer the operator receives is the
 * one AuthUser gave.</p>
 *
 * <p>Only refusals an operator can act on are passed through. Anything else -
 * including a downstream 401, which means our relayed token was rejected rather
 * than the operator's being invalid - is an upstream failure and becomes a
 * 502.</p>
 *
 * <p>No downstream response body is forwarded. AuthUser speaks to the Auth0
 * Management API, and its error text can carry tenant and connection detail
 * that has no business reaching a caller here, so each status carries a short
 * message of its own.</p>
 *
 * <p>This mirrors the decoders in PaymentOrchestrator and AICommerceAgent
 * rather than sharing one with them. The three word these messages for
 * different readers, and the mapping is not identical either: those two treat
 * 422 as insufficient funds, which means nothing on a registration call, so it
 * is deliberately absent here and falls through to the upstream case. Backlog
 * item 5 records why the three are still separate.</p>
 *
 * <p>Declared as a bean, so it applies to every Feign client in this service.
 * There is one today, {@link AuthServiceClient}.</p>
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
                    "This platform may not register customers in the identity provider");
            case 404 -> new ResourceNotFoundException(
                    "The identity provider did not recognise this registration request");
            case 409 -> new ConflictException(
                    "That customer is already registered in the identity provider");
            default -> new UpstreamException(
                    "The customer could not be registered right now; please try again");
        };
    }

    /** The Feign interface a call was made through, for the log line only. */
    private static String serviceOf(String methodKey) {
        int hash = methodKey.indexOf('#');
        return hash > 0 ? methodKey.substring(0, hash) : methodKey;
    }
}
