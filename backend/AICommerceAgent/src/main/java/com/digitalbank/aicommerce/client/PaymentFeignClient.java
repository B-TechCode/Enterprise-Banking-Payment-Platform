package com.digitalbank.aicommerce.client;

import org.springframework.cloud.openfeign.FeignClient;

import com.commons.security.FeignTokenRelayConfig;

/**
 * Initiates a bill payment. Called only from the confirmation path, never from
 * a tool the model can invoke.
 */
@FeignClient(
        name = "payment-orchestrator",
        configuration = FeignTokenRelayConfig.class)
public interface PaymentFeignClient {

    // TODO: declare the endpoints this service calls.
}
