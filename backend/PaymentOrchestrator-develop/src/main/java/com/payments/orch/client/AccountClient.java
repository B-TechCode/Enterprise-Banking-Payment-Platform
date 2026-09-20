package com.payments.orch.client;

import java.util.UUID;

import com.account.dto.AccountOwnerResponse;
import com.account.dto.AccountResponse;
import com.account.dto.CreateHoldRequest;
import com.account.dto.HoldResponse;
import com.account.dto.PostingRequest;
import com.commons.security.FeignTokenRelayConfig;

import jakarta.validation.Valid;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(
        name = "account-service",
        url = "${account.service.url}",         
        configuration = FeignTokenRelayConfig.class  
)
public interface AccountClient {

    /**
     * Who owns an account, asked with the caller's own relayed token.
     *
     * <p>Only used for payments accepted before the orchestrator recorded the
     * customer on the payment itself. Account Service applies its own ownership
     * check to the relayed token, so a caller asking about an account that is
     * not theirs is refused there.</p>
     */
    @GetMapping("/api/v1/accounts/{id}/owner")
    AccountOwnerResponse getOwner(@PathVariable("id") UUID id);

    @PostMapping("/api/v1/accounts/{id}/holds")
    HoldResponse placeHold(
            @PathVariable("id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateHoldRequest request
    );

   
    
}
