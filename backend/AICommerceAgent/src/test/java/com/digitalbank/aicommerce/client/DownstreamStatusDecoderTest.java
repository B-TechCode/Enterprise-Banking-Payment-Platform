package com.digitalbank.aicommerce.client;

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
 * What a customer is told when another service refuses the agent.
 *
 * <p>Feign's default decoder raises a FeignException that nothing here handles,
 * so every answer from the Payment Orchestrator, Account Service or Biller
 * Service became a 500: a payment declined for want of funds was reported as
 * the assistant having failed.</p>
 */
class DownstreamStatusDecoderTest {

    private final ErrorDecoder decoder = new DownstreamStatusDecoder().feignErrorDecoder();

    private static Response responseWith(int status, String body) {
        return Response.builder()
                .status(status)
                .reason("reason")
                .request(Request.create(Request.HttpMethod.POST, "http://payment-orchestrator/api/v1/payments/billpay",
                        Collections.emptyMap(), null, StandardCharsets.UTF_8, null))
                .headers(Collections.emptyMap())
                .body(body, StandardCharsets.UTF_8)
                .build();
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of("not the customer's account", 403, ForbiddenException.class),
                Arguments.of("account or biller missing", 404, ResourceNotFoundException.class),
                Arguments.of("state changed underneath", 409, ConflictException.class),
                Arguments.of("not enough money", 422, InsufficientFundsException.class));
    }

    @ParameterizedTest(name = "{1} {0}")
    @MethodSource("refusals")
    @DisplayName("a refusal the customer can act on is passed through")
    void refusalsArePassedThrough(String reason, int status, Class<?> expected) {
        assertThat(decoder.decode("PaymentFeignClient#billPay(String,BillPayCommand)",
                responseWith(status, "{\"error\":\"denied\"}")))
                .isInstanceOf(expected);
    }

    @Test
    @DisplayName("a downstream 401 is an upstream failure, not a challenge to the customer")
    void downstreamUnauthorizedBecomesUpstreamFailure() {
        assertThat(decoder.decode("PaymentFeignClient#billPay", responseWith(401, "")))
                .isInstanceOf(UpstreamException.class);
    }

    @Test
    @DisplayName("a downstream server error is an upstream failure")
    void downstreamServerErrorBecomesUpstreamFailure() {
        assertThat(decoder.decode("PaymentFeignClient#billPay", responseWith(500, "")))
                .isInstanceOf(UpstreamException.class);
    }

    @Test
    @DisplayName("an unmapped 4xx is not silently treated as a refusal the customer caused")
    void unmappedClientErrorBecomesUpstreamFailure() {
        assertThat(decoder.decode("PaymentFeignClient#billPay", responseWith(418, "")))
                .isInstanceOf(UpstreamException.class);
    }

    @Test
    @DisplayName("each status carries its own message, so nothing downstream reaches the customer through it")
    void messagesAreFixedAndCarryNothingDownstream() {
        // Asserting the exact message rather than the absence of particular
        // words is what gives this teeth: appending anything at all - a body, a
        // status, a stack frame - fails here.
        String secrets = "{\"sql\":\"select * from payments where customer_id='cust-7'\","
                + "\"stack\":\"com.payments.orch.service.BillPayOrchestrator\"}";

        assertThat(decoder.decode("PaymentFeignClient#billPay", responseWith(403, secrets)).getMessage())
                .isEqualTo("That account is not yours to pay from");
        assertThat(decoder.decode("PaymentFeignClient#billPay", responseWith(404, secrets)).getMessage())
                .isEqualTo("The account or biller for this payment was not found");
        assertThat(decoder.decode("PaymentFeignClient#billPay", responseWith(409, secrets)).getMessage())
                .isEqualTo("Something about this payment changed; please ask again");
        assertThat(decoder.decode("PaymentFeignClient#billPay", responseWith(422, secrets)).getMessage())
                .isEqualTo("Insufficient Funds");
        assertThat(decoder.decode("PaymentFeignClient#billPay", responseWith(500, secrets)).getMessage())
                .isEqualTo("The payment could not be submitted right now; you can retry");
    }
}
