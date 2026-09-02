package com.digitalbank.aicommerce.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.account.dto.AccountResponse;
import com.commons.security.FeignTokenRelayConfig;

/**
 * Reads the accounts of the caller from Account Service.
 *
 * <p>The token of the caller is relayed, so Account Service applies its own
 * ownership check to every request. The agent therefore cannot read an account
 * the caller could not read directly.</p>
 */
@FeignClient(
        name = "account-service",
        url = "${account.service.url}",
        configuration = FeignTokenRelayConfig.class)
public interface AccountFeignClient {

    @GetMapping("/api/v1/customer/{customerId}/accounts")
    List<AccountResponse> findAccountsByCustomer(@PathVariable("customerId") String customerId);
}
