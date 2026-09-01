package com.digitalbank.aicommerce.client;

import org.springframework.cloud.openfeign.FeignClient;

import com.commons.security.FeignTokenRelayConfig;

/**
 * Resolves and validates billers while a payment is being proposed.
 */
@FeignClient(
        name = "biller-service",
        configuration = FeignTokenRelayConfig.class)
public interface BillerFeignClient {

    // TODO: declare the endpoints this service calls.
}
