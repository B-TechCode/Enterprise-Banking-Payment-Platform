package com.digitalbank.aicommerce.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import com.commons.security.FeignTokenRelayConfig;
import com.digitalbank.aicommerce.client.dto.BillPayCommand;
import com.digitalbank.aicommerce.client.dto.PaymentAcceptedView;

/**
 * Initiates a bill payment. Called only from the confirmation path, never from
 * a tool the model can invoke.
 *
 * <p>This is the one interface in the service that can move money. It is
 * injected into {@code ProposalService} and nowhere else; no {@code AgentTool}
 * implementation may reach it, directly or transitively, and a test asserts
 * that. If this client ever becomes reachable from a tool, the route from model
 * output to a payment is open, which is the single thing the design forbids.</p>
 */
@FeignClient(
        name = "payment-orchestrator",
        url = "${payment.service.url}",
        configuration = FeignTokenRelayConfig.class)
public interface PaymentFeignClient {

    /**
     * @param idempotencyKey the proposal id, so a retried confirmation settles
     *                       onto the same payment rather than creating a second
     */
    @PostMapping("/api/v1/payments/billpay")
    PaymentAcceptedView billPay(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                @RequestBody BillPayCommand command);
}
