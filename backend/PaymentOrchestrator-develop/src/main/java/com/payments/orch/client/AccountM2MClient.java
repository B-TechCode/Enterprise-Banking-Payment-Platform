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

   
	  /**
	   * Takes the held funds in one step, instead of releasing the hold and then
	   * debiting. Between those two calls the funds are spendable, and a debit
	   * that then fails leaves the payment uncollectable with its hold gone.
	   *
	   * @param idempotencyKey derived from the payment id, so a redelivered
	   *                       confirmation captures once
	   */
	  @PostMapping("/api/v1/accounts/{accountId}/holds/{holdId}/capture")
	  HoldResponse captureHold(
	      @PathVariable("accountId") UUID accountId,
	      @PathVariable("holdId") UUID holdId,
	      @RequestHeader(name = "Idempotency-Key") String idempotencyKey
	  );

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
