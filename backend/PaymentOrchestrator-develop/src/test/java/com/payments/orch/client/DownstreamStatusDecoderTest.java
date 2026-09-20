package com.payments.orch.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.commons.exception.ConflictException;
import com.commons.exception.ForbiddenException;
import com.commons.exception.InsufficientFundsException;
import com.commons.exception.ResourceNotFoundException;
import com.commons.exception.UpstreamException;

import feign.Request;
import feign.Response;
import feign.codec.ErrorDecoder;

/**
 * What a caller is told when another service refuses us.
 *
 * <p>Feign's default decoder raises a FeignException that nothing handles, so
 * the shared handler's catch-all reported every downstream answer as a 500: a
 * customer paying from an account they do not own was told the server had
 * failed. Each status below maps to the exception the shared handler turns back
 * into that same status.</p>
 */
class DownstreamStatusDecoderTest {

    private final ErrorDecoder decoder = new DownstreamStatusDecoder().feignErrorDecoder();

    private static Response responseWith(int status, String body) {
        return Response.builder()
                .status(status)
                .reason("reason")
                .request(Request.create(Request.HttpMethod.POST, "http://account-service/api/v1/accounts",
                        Collections.emptyMap(), null, StandardCharsets.UTF_8, null))
                .headers(Collections.emptyMap())
                .body(body, StandardCharsets.UTF_8)
                .build();
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of("not the caller's account", 403, ForbiddenException.class),
                Arguments.of("account does not exist", 404, ResourceNotFoundException.class),
                Arguments.of("account changed underneath", 409, ConflictException.class),
                Arguments.of("not enough money", 422, InsufficientFundsException.class));
    }

    @ParameterizedTest(name = "{1} {0}")
    @MethodSource("refusals")
    @DisplayName("a refusal the caller can act on is passed through")
    void refusalsArePassedThrough(String reason, int status, Class<?> expected) {
        Exception translated = decoder.decode("AccountClient#placeHold(UUID,String,CreateHoldRequest)",
                responseWith(status, "{\"error\":\"denied\"}"));

        assertThat(translated).isInstanceOf(expected);
    }

    @Test
    @DisplayName("a downstream 401 is an upstream failure, not a challenge to the caller")
    void downstreamUnauthorizedBecomesUpstreamFailure() {
        // Our caller's token was accepted here. A 401 downstream means the
        // relayed token was rejected, which is our problem, not theirs.
        assertThat(decoder.decode("AccountClient#placeHold", responseWith(401, "")))
                .isInstanceOf(UpstreamException.class);
    }

    @Test
    @DisplayName("a downstream server error is an upstream failure")
    void downstreamServerErrorBecomesUpstreamFailure() {
        assertThat(decoder.decode("AccountClient#placeHold", responseWith(500, "")))
                .isInstanceOf(UpstreamException.class);
    }

    @Test
    @DisplayName("an unmapped 4xx is not silently treated as a refusal the caller caused")
    void unmappedClientErrorBecomesUpstreamFailure() {
        assertThat(decoder.decode("AccountClient#placeHold", responseWith(418, "")))
                .isInstanceOf(UpstreamException.class);
    }

    @Test
    @DisplayName("each status carries its own message, so nothing downstream can reach our caller through it")
    void messagesAreFixedAndCarryNothingDownstream() {
        // Another service's error text can describe internals, and these
        // endpoints are reachable from outside. Asserting the exact message
        // rather than the absence of particular words is what gives this teeth:
        // appending anything at all - a body, a status, a stack frame - fails
        // here, so a leak cannot be introduced quietly.
        String secrets = "{\"sql\":\"select * from account where customer_id='cust-7'\","
                + "\"stack\":\"com.account.service.AccountService.ensureOwnerOrAdmin\"}";

        assertThat(decoder.decode("AccountClient#placeHold", responseWith(403, secrets)).getMessage())
                .isEqualTo("You may not use that account for this payment");
        assertThat(decoder.decode("AccountClient#placeHold", responseWith(404, secrets)).getMessage())
                .isEqualTo("The account for this payment was not found");
        assertThat(decoder.decode("AccountClient#placeHold", responseWith(409, secrets)).getMessage())
                .isEqualTo("That account changed while this payment was being prepared; try again");
        assertThat(decoder.decode("AccountClient#placeHold", responseWith(422, secrets)).getMessage())
                .isEqualTo("Insufficient Funds");
        assertThat(decoder.decode("AccountClient#placeHold", responseWith(500, secrets)).getMessage())
                .isEqualTo("The payment could not be completed right now; please try again");
    }
}
