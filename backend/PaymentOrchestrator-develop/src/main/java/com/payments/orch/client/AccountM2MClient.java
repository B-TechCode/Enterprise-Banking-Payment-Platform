package com.payments.orch.client;

import java.util.UUID;

import com.account.dto.AccountResponse;
import com.account.dto.CreateHoldRequest;
import com.account.dto.HoldResponse;
import com.account.dto.PostingRequest;
import com.commons.security.FeignTokenRelayConfig;

import jakarta.validation.Valid;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(
        name = "account-servicem2m",
        url = "${account.service.url}",         
        configuration = com.payments.orch.security.FeignM2MOAuth2Config.class
)
public interface AccountM2MClient {

   
	  @PostMapping("/api/v1/accounts/{accountId}/holds/{holdId}/release")
	  HoldResponse releaseHold(
	      @PathVariable("accountId") UUID accountId,
	      @PathVariable("holdId") UUID holdId,
	      @RequestHeader(name = "Idempotency-Key") String idempotencyKey
	  );
    
    
	  /**
	   * @param idempotencyKey the payment id. Settlement confirmations arrive at
	   *                       least once and a local failure after this call can
	   *                       roll back everything except the debit itself, so
	   *                       the key is what stops the customer being charged
	   *                       twice.
	   */
	  @PostMapping("/api/v1/accounts/{id}/debit")
	  AccountResponse debit(
	      @PathVariable("id") UUID id,
	      @RequestHeader(name = "If-Match", required = false) String ifMatch,
	      @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
	      @Valid @RequestBody PostingRequest request
	  );
}
