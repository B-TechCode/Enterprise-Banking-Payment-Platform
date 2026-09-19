package com.digitalbank.aicommerce.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.commons.security.FeignTokenRelayConfig;
import com.digitalbank.aicommerce.client.dto.BillerPage;

/**
 * Resolves and validates billers while a payment is being proposed.
 *
 * <p>The token of the caller is relayed, so the list returned is the caller's
 * own set of billers as Biller Service scopes it. The agent cannot enumerate
 * billers belonging to anyone else.</p>
 */
@FeignClient(
        name = "biller-service",
        url = "${biller.service.url}",
        configuration = FeignTokenRelayConfig.class)
public interface BillerFeignClient {

    @GetMapping("/api/v1/billers")
    BillerPage listBillers(@RequestParam("limit") int limit,
                           @RequestParam("offset") int offset);

    /**
     * Registry-style check that a reference number is payable. Consulted in
     * addition to the caller's own list, so a biller that is still listed but no
     * longer active cannot be paid.
     */
    @GetMapping("/api/v1/billers/{refNum}/active")
    Boolean isActive(@PathVariable("refNum") String refNum);
}
