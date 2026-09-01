package com.digitalbank.aicommerce.client;

import org.springframework.cloud.openfeign.FeignClient;

import com.commons.security.FeignTokenRelayConfig;

/**
 * Reads the accounts and balances of the caller. The token of the caller is
 * relayed, so Account Service applies its own ownership check to every request.
 */
@FeignClient(
        name = "account-service",
        configuration = FeignTokenRelayConfig.class)
public interface AccountFeignClient {

    // TODO: declare the endpoints this service calls.
}
