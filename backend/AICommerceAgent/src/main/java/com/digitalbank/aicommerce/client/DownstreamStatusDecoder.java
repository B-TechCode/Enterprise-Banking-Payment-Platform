package com.digitalbank.aicommerce.client;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.commons.exception.ConflictException;
import com.commons.exception.ForbiddenException;
import com.commons.exception.InsufficientFundsException;
import com.commons.exception.ResourceNotFoundException;
import com.commons.exception.UpstreamException;

import feign.Response;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns a refusal from another service into the same refusal from this one.
 *
 * <p>Feign's default decoder raises a FeignException that nothing here handles,
 * so every answer from the Payment Orchestrator, Account Service or Biller
 * Service became a 500. A customer whose payment was declined for want of funds
 * was told the assistant had failed, which is both wrong and no help to someone
 * who could have acted on the real reason.</p>
 *
 * <p>Each status maps to the exception the shared handler already translates
 * back into that status, so the answer the caller receives is the one the
 * downstream service gave.</p>
 *
 * <p>A downstream 401 is deliberately not forwarded: the caller's token was
 * accepted here, so a 401 further in means the relayed token was rejected,
 * which is this platform's problem rather than a challenge to them.</p>
 *
 * <p>No downstream response body is passed on. The messages below are written
 * to be read by a person, because this service answers a conversation, and
 * another service's error text can describe internals they should not see.</p>
 *
 * <p>This mirrors the decoder in the Payment Orchestrator rather than sharing
 * one with it. The two services word these messages for different audiences,
 * and a shared bean would be picked up by every service scanning com.commons.
 * Worth extracting if a third service needs it.</p>
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

        log.warn("{} answered {}", methodKey, response.status());

        return switch (response.status()) {
            case 403 -> new ForbiddenException(
                    "That account is not yours to pay from");
            case 404 -> new ResourceNotFoundException(
                    "The account or biller for this payment was not found");
            case 409 -> new ConflictException(
                    "Something about this payment changed; please ask again");
            case 422 -> new InsufficientFundsException();
            default -> new UpstreamException(
                    "The payment could not be submitted right now; you can retry");
        };
    }
}
