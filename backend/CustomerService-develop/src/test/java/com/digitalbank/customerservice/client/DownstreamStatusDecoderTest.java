package com.digitalbank.customerservice.client;

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
import com.commons.exception.ResourceNotFoundException;
import com.commons.exception.UpstreamException;

import feign.Request;
import feign.Response;
import feign.codec.ErrorDecoder;

/**
 * What an operator is told when AuthUser refuses a customer registration.
 *
 * <p>Feign's default decoder raises a FeignException that nothing here handles,
 * so every answer from AuthUser became a 500: a refused registration was
 * reported as the server having failed, which reads the same as an outage and
 * leaves nothing to act on.</p>
 */
class DownstreamStatusDecoderTest {

    private static final String METHOD_KEY =
            "AuthServiceClient#registerCustomer(CustomerRegistrationRequest)";

    private final ErrorDecoder decoder = new DownstreamStatusDecoder().feignErrorDecoder();

    private static Response responseWith(int status, String body) {
        return Response.builder()
                .status(status)
                .reason("reason")
                .request(Request.create(Request.HttpMethod.POST,
                        "http://auth-service/api/v1/iam/users",
                        Collections.emptyMap(), null, StandardCharsets.UTF_8, null))
                .headers(Collections.emptyMap())
                .body(body, StandardCharsets.UTF_8)
                .build();
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of("this platform may not register users", 403, ForbiddenException.class),
                Arguments.of("registration endpoint not recognised", 404, ResourceNotFoundException.class),
                Arguments.of("customer already registered", 409, ConflictException.class));
    }

    @ParameterizedTest(name = "{1} {0}")
    @MethodSource("refusals")
    @DisplayName("a refusal an operator can act on is passed through")
    void refusalsArePassedThrough(String reason, int status, Class<?> expected) {
        assertThat(decoder.decode(METHOD_KEY, responseWith(status, "{\"error\":\"denied\"}")))
                .isInstanceOf(expected);
    }

    @Test
    @DisplayName("a downstream 401 is an upstream failure, not a challenge to the operator")
    void downstreamUnauthorizedBecomesUpstreamFailure() {
        // A 401 here means the token this service relayed was rejected, not
        // that the operator's own token was bad. Passing it through would ask
        // them to re-authenticate against a problem they cannot fix.
        assertThat(decoder.decode(METHOD_KEY, responseWith(401, "")))
                .isInstanceOf(UpstreamException.class);
    }

    @Test
    @DisplayName("a downstream server error is an upstream failure")
    void downstreamServerErrorBecomesUpstreamFailure() {
        assertThat(decoder.decode(METHOD_KEY, responseWith(500, "")))
                .isInstanceOf(UpstreamException.class);
    }

    @Test
    @DisplayName("422 is not borrowed from the payment decoders")
    void unprocessableIsNotTreatedAsInsufficientFunds() {
        // The other two copies map 422 to InsufficientFundsException. That means
        // nothing on a registration call, so it is deliberately absent here and
        // falls through to the upstream case. This test exists so that copying
        // the payment mapping back in is a deliberate act, not a quiet one.
        assertThat(decoder.decode(METHOD_KEY, responseWith(422, "")))
                .isInstanceOf(UpstreamException.class);
    }

    @Test
    @DisplayName("an unmapped 4xx is not silently treated as a refusal the operator caused")
    void unmappedClientErrorBecomesUpstreamFailure() {
        assertThat(decoder.decode(METHOD_KEY, responseWith(418, "")))
                .isInstanceOf(UpstreamException.class);
    }

    @Test
    @DisplayName("each status carries its own message, so nothing downstream reaches the operator through it")
    void messagesAreFixedAndCarryNothingDownstream() {
        // Asserting the exact message rather than the absence of particular
        // words is what gives this teeth: appending anything at all - a body, a
        // status, a stack frame - fails here. AuthUser talks to the Auth0
        // Management API, so its error text can name the tenant and connection.
        String leaky = "{\"connection\":\"Username-Password-Authentication\","
                + "\"tenant\":\"dev-vkxlfm4oy207h5bq\","
                + "\"stack\":\"com.authuser.service.Auth0UserService.createDbUser\"}";

        assertThat(decoder.decode(METHOD_KEY, responseWith(403, leaky)).getMessage())
                .isEqualTo("This platform may not register customers in the identity provider");
        assertThat(decoder.decode(METHOD_KEY, responseWith(404, leaky)).getMessage())
                .isEqualTo("The identity provider did not recognise this registration request");
        assertThat(decoder.decode(METHOD_KEY, responseWith(409, leaky)).getMessage())
                .isEqualTo("That customer is already registered in the identity provider");
        assertThat(decoder.decode(METHOD_KEY, responseWith(500, leaky)).getMessage())
                .isEqualTo("The customer could not be registered right now; please try again");
    }
}
