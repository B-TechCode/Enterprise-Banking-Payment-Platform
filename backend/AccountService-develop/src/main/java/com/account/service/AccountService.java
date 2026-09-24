package com.account.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.account.dto.*;
import com.account.mapper.AccountMapper;
import com.account.model.*;
import com.account.repository.AccountHoldRepository;
import com.account.repository.AccountRepository;
import com.commons.exception.OwnerAccessDeniedException;
import com.commons.security.CurrentUser;
import com.account.dto.TransactionRequest;
import com.account.model.Transaction;
import com.account.mapper.TransactionMapper;
import org.apache.commons.codec.digest.DigestUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

	private final AccountRepository accountRepo;
	private final AccountHoldRepository holdRepo;
	private final AccountMapper mapper;
	private final TransactionService transactionService;
	private final TransactionMapper transactionMapper;

	private final CurrentUser currentUser;

	/* ---------------- Utility ---------------- */

	/**
	 * Identifies a create request so that a retry of it returns the account it
	 * made instead of making another.
	 *
	 * <p>The caller's Idempotency-Key when it sent one, stored as given. Without
	 * one, a SHA-256 of the request's fields, customer id included. It replaced
	 * a 32-bit String.hashCode, small enough to collide by accident at volume.
	 * Either way the value is unique per customer, not across the table, and is
	 * only ever looked up together with the customer id.</p>
	 *
	 * <p>Existing fingerprints were not rewritten when the hash changed, so a
	 * keyless create sent before that deploy and retried after it does not match
	 * and makes a second account. V1__scope_idempotency_fingerprints records why
	 * that one-deploy window was accepted rather than backfilled.</p>
	 */
	private static String fingerprintForCreate(AccountRequest r, String idempotencyKey) {
		if (idempotencyKey != null && !idempotencyKey.isBlank())
			return idempotencyKey.trim();
		String base = (r.customerId() + "|" + r.accountType() + "|" + r.accountSubType() + "|" + r.currency() + "|"
				+ r.nickname() + "|" + r.displayName()).toUpperCase();
		return DigestUtils.sha256Hex(base);
	}

	private BigDecimal activeHoldsTotal(UUID accountId) {
		return holdRepo.findByAccountIdAndStatus(accountId, HoldStatus.ACTIVE).stream().map(AccountHold::getAmount)
				.reduce(BigDecimal.ZERO, BigDecimal::add);

	}

	/**
	 * Enforces that the caller may act on the given account.
	 *
	 * <p>Three cases are allowed:</p>
	 * <ol>
	 *   <li><b>Administrators</b> - tokens holding {@code admin:accounts}.</li>
	 *   <li><b>Service callers</b> - client-credentials tokens, which represent a
	 *       trusted internal service (e.g. the Payment Orchestrator placing a
	 *       hold) and carry no {@code customer_id} claim to compare against.</li>
	 *   <li><b>The account owner</b> - a user token whose {@code customer_id}
	 *       claim matches the account's customer.</li>
	 * </ol>
	 *
	 * <p>The service bypass is deliberately keyed on the grant type combined with
	 * the <i>absence</i> of a customer identity, not on a scope. Keying it on a
	 * scope such as {@code fdx:accounts.write} would also exempt ordinary user
	 * tokens that legitimately hold that scope, removing the ownership check for
	 * them and allowing one customer to act on another customer's account.</p>
	 */
	/**
	 * Enforces that the caller may bring money into the platform.
	 *
	 * <p>Crediting an account and opening one with a balance are the only two
	 * ways a balance can appear from nothing: the platform has no deposit,
	 * transfer or external funding domain, so neither operation can name where
	 * the money came from, and the ledger has no field to record it in. Ownership
	 * is therefore the wrong test - it would let a customer fabricate their own
	 * money - and so is the service bypass, since no service calls either path.
	 * Only an administrator provisioning a demo account may.</p>
	 *
	 * <p>This mirrors the scope on the endpoints. It is repeated here so the rule
	 * survives a controller being re-annotated or another caller being added;
	 * AccountFundingAuthorizationTest pins both halves.</p>
	 */
	private void ensureMayCreateMoney() {
		if (!currentUser.hasScope("admin:accounts")) {
			throw new OwnerAccessDeniedException();
		}
	}

	private void ensureOwnerOrAdmin(Account a) {

		// 1) Administrative access.
		if (currentUser.hasScope("admin:accounts")) {
			return;
		}

		var claimedCustomerId = currentUser.customerIdClaim();

		// 2) Trusted service-to-service call: client-credentials grant with no
		//    customer identity to check against. A client-credentials token that
		//    *does* carry customer_id is still held to the ownership check below.
		if (currentUser.isClientCredentials() && claimedCustomerId.isEmpty()) {
			return;
		}

		// 3) End-user access: must present a customer_id and own the account.
		var me = claimedCustomerId.orElseThrow(OwnerAccessDeniedException::new);

		if (!a.getCustomerId().equals(me)) {
			throw new OwnerAccessDeniedException();
		}
	}

	/**
	 * Ownership check keyed on a customer id rather than a loaded account, for
	 * queries that select by customer instead of by account id.
	 */
	private void ensureOwnerOrAdmin(String customerId) {

		if (currentUser.hasScope("admin:accounts")) {
			return;
		}

		var claimedCustomerId = currentUser.customerIdClaim();

		if (currentUser.isClientCredentials() && claimedCustomerId.isEmpty()) {
			return;
		}

		var me = claimedCustomerId.orElseThrow(OwnerAccessDeniedException::new);

		if (!me.equals(customerId)) {
			throw new OwnerAccessDeniedException();
		}
	}

	/**
	 * Administrative access only.
	 *
	 * <p>Used by operations that span every customer, where there is no single
	 * owner to compare against. The controller also gates these with an admin
	 * scope; this is the service-level backstop, because a service method is
	 * reachable from any in-process caller, not only through its controller.</p>
	 *
	 * <p>Deliberately does not admit client-credentials callers: no service on
	 * the platform needs to enumerate every account.</p>
	 */
	private void ensureAdmin() {

		if (currentUser.hasScope("admin:accounts")
				|| currentUser.hasScope("admin:accounts.read")) {
			return;
		}

		throw new OwnerAccessDeniedException();
	}

	/**
	 * Whether this posting has already been applied to this account.
	 *
	 * <p>Callers that may retry - a Kafka consumer redelivering an event, a
	 * client resending after a timeout - pass a key that is stable across those
	 * retries. The first posting records it as the transaction's fingerprint, so
	 * a repeat is recognised here and changes nothing.</p>
	 *
	 * <p>This check guards the balance, not the ledger. TransactionService.save
	 * already returns an existing transaction for a repeated fingerprint, so
	 * without this the balance would move twice while only one transaction was
	 * recorded, and the ledger would no longer explain the balance.</p>
	 *
	 * <p>It is a fast path rather than the guarantee. Two concurrent retries can
	 * both pass it; the unique constraint on (accountId, requestFingerprint)
	 * then fails the second, and because the transaction is written in the same
	 * database transaction as the balance, that debit or credit rolls back
	 * with it.</p>
	 */
	private boolean alreadyPosted(UUID accountId, String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			return false;
		}
		return transactionService.findByAccountAndFingerprint(accountId, idempotencyKey.trim()).isPresent();
	}

	/** The caller's key when it supplied one; otherwise the legacy time-based fingerprint. */
	private static String fingerprintFor(String idempotencyKey, Account acc, String type, BigDecimal amount,
			String reason, OffsetDateTime occurredAt) {

		if (idempotencyKey != null && !idempotencyKey.isBlank()) {
			return idempotencyKey.trim();
		}

		// Includes the timestamp, so it is unique per call: a posting made
		// without a key is applied every time it is asked for, as before.
		return DigestUtils.sha256Hex(
				acc.getId().toString() + type + amount.toPlainString() + reason + occurredAt.toString());
	}

	private void emitTransaction(Account acc, String type, BigDecimal amount, String reason, boolean posting,
			BigDecimal balanceAfterOrNull) {
		emitTransaction(acc, type, amount, reason, posting, balanceAfterOrNull, null);
	}

	private void emitTransaction(Account acc, String type, BigDecimal amount, String reason, boolean posting,
			BigDecimal balanceAfterOrNull, String idempotencyKey) {

		String currency = acc.getCurrency();

// Build DTO
		TransactionRequest req = new TransactionRequest(acc.getId(), // accountId
				amount, // amount
				currency, // currency
				type, // type: DEBIT/CREDIT/HOLD_PLACED/HOLD_RELEASED
				reason, // reason
				posting ? balanceAfterOrNull : null, // balanceAfter (only for postings)
				OffsetDateTime.now(ZoneOffset.UTC) // occurredAt
		);

// Map to JPA entity
		Transaction tx = transactionMapper.toEntity(req);

		tx.setRequestFingerprint(
				fingerprintFor(idempotencyKey, acc, type, amount, reason, req.occurredAt()));

// Persist using same DB + same Spring transaction
		transactionService.save(tx);
	}

	/* ---------------- Queries ---------------- */

	public List<AccountResponse> listAll() {
		ensureAdmin();
		return accountRepo.findAll().stream().map(mapper::toDto).toList();
	}

	public AccountResponse get(UUID id) {
		Account a = accountRepo.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Account not found"));
		ensureOwnerOrAdmin(a);
		return mapper.toDto(a);
	}

	public AccountBalanceResponse getBalance(UUID id) {
		Account a = accountRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("Account not found"));
		ensureOwnerOrAdmin(a);
		BigDecimal holds = activeHoldsTotal(id);
		BigDecimal available = a.getBalance().subtract(holds);
		return new AccountBalanceResponse(a.getBalance(), holds, available);
	}

	/** NEW: used by GET /customer/{id}/accounts */
	public List<AccountResponse> findByCustomerId(String customerId) {
		ensureOwnerOrAdmin(customerId);
		return accountRepo.findByCustomerId(customerId).stream().map(mapper::toDto).toList();
	}

	/** NEW: used by PATCH /accounts/{id}/status */
	@Transactional
	public void updateStatus(UUID id, AccountStatus status) {
		Account a = accountRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("Account not found"));
		ensureOwnerOrAdmin(a);
		a.setStatus(status);
		accountRepo.save(a);
	}

	/** NEW: used by GET /accounts/{id}/owner */
	public String getCustomerIdForAccount(UUID id) {
		Account a = accountRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("Account not found"));
		ensureOwnerOrAdmin(a);
		return a.getCustomerId();
	}

	/* ---------------- Commands ---------------- */

	@Transactional
	public AccountResponse create(AccountRequest request, String idempotencyKey) {
		String fp = fingerprintForCreate(request, idempotencyKey);

		// Whether the caller may create for this customer is settled before
		// anything is looked up on that customer's behalf.
		ensureOwnerOrAdmin(request.customerId());

		// Scoped to the customer, as the unique constraint is: one customer's
		// fingerprint or Idempotency-Key can no longer match another's account.
		Optional<Account> existing = accountRepo.findByCustomerIdAndRequestFingerprint(request.customerId(), fp);
		if (existing.isPresent()) {
			Account a = existing.get();
			// The scoped lookup can only return this customer's account, so
			// this cannot fail today. It stays as a second layer: before the
			// lookup was scoped, a match here handed another customer's account
			// and balance back with no check at all, and the replay path should
			// not depend on the query's scoping alone to prevent that again.
			ensureOwnerOrAdmin(a);
			return mapper.toDto(a);
		}

		Account entity = mapper.toEntity(request);
		ensureOwnerOrAdmin(entity);
		entity.setRequestFingerprint(fp);

		// An opening balance is money appearing from nothing, exactly as a credit
		// is. Opening an account at zero stays open to any customer; asking for a
		// balance with it does not. Without this, restricting credit alone would
		// achieve nothing: a customer could open an account at any balance.
		BigDecimal openingBalance =
				request.openingBalance() == null ? BigDecimal.ZERO : request.openingBalance();
		if (openingBalance.signum() > 0) {
			ensureMayCreateMoney();
		}
		entity.setBalance(openingBalance);

		// naive account number generator — replace with real BIN/range later
		entity.setAccountNumber("9" + Math.abs((int) System.nanoTime()));

		return mapper.toDto(accountRepo.save(entity));
	}

	@Transactional(propagation = Propagation.REQUIRED)
	public AccountResponse credit(UUID id, PostingRequest r, Integer expectedVersion) {
		return credit(id, r, expectedVersion, null);
	}

	@Transactional(propagation = Propagation.REQUIRED)
	public AccountResponse credit(UUID id, PostingRequest r, Integer expectedVersion, String idempotencyKey) {
		// Checked before the account is even loaded: a customer may not credit
		// any account, their own included, so there is nothing to look up on
		// their behalf.
		ensureMayCreateMoney();

		Account a = accountRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("Account not found"));
		ensureOwnerOrAdmin(a);

		// A repeat of a posting already applied changes nothing and reports the
		// account as it stands.
		if (alreadyPosted(id, idempotencyKey)) {
			return mapper.toDto(a);
		}

		if (expectedVersion != null && !expectedVersion.equals(a.getVersion())) {
			throw new IllegalStateException("ETag mismatch");
		}
		a.setBalance(a.getBalance().add(r.amount()));

		Account saved = accountRepo.saveAndFlush(a);

		emitTransaction(saved, "CREDIT", r.amount(), r.reason(), true, saved.getBalance(), idempotencyKey);
		return mapper.toDto(saved);
	}

	@Transactional(propagation = Propagation.REQUIRED)
	public AccountResponse debit(UUID id, PostingRequest r, Integer expectedVersion) {
		return debit(id, r, expectedVersion, null);
	}

	/**
	 * @param idempotencyKey stable across retries of the same posting; a repeat
	 *                       takes no money and returns the account unchanged
	 */
	@Transactional(propagation = Propagation.REQUIRED)
	public AccountResponse debit(UUID id, PostingRequest r, Integer expectedVersion, String idempotencyKey) {
		Account a = accountRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("Account not found"));
		if (expectedVersion != null && !expectedVersion.equals(a.getVersion())) {
			throw new IllegalStateException("ETag mismatch");
		}
		ensureOwnerOrAdmin(a);

		// The Payment Orchestrator debits with the payment id as its key, so a
		// settlement confirmation processed twice takes the money once.
		if (alreadyPosted(id, idempotencyKey)) {
			return mapper.toDto(a);
		}

		BigDecimal holds = activeHoldsTotal(id);
		BigDecimal available = a.getBalance().subtract(holds);
		if (r.amount().compareTo(available) > 0) {
			throw new IllegalArgumentException("Insufficient available funds");
		}
		a.setBalance(a.getBalance().subtract(r.amount()));

		Account saved = accountRepo.saveAndFlush(a);

		emitTransaction(saved, "DEBIT", r.amount(), r.reason(), true, saved.getBalance(), idempotencyKey);
		return mapper.toDto(saved);
	}

	@Transactional(propagation = Propagation.REQUIRED)
	public HoldResponse createHold(UUID accountId, CreateHoldRequest r) {
		Account a = accountRepo.findById(accountId)
				.orElseThrow(() -> new IllegalArgumentException("Account not found"));
		ensureOwnerOrAdmin(a);

		String fp = (r.idempotencyKey() != null && !r.idempotencyKey().isBlank()) ? r.idempotencyKey().trim() : null;
		if (fp != null) {
			// Scoped to this account: an unscoped lookup returned whichever hold
			// carried the key, so a caller reusing another customer's key was
			// handed that customer's hold id and amount.
			//
			// The column's unique constraint is still global, so a key that
			// another account already used now fails the write rather than
			// leaking. Making the constraint per-account needs a migration and
			// is tracked separately.
			Optional<AccountHold> ex = holdRepo.findByAccountIdAndRequestFingerprint(accountId, fp);
			if (ex.isPresent()) {
				AccountHold h = ex.get();
				return new HoldResponse(h.getId(), h.getAmount(), h.getStatus(), h.getCreatedAt(), h.getReleaseAt());
			}
		}

		BigDecimal holds = activeHoldsTotal(accountId);
		BigDecimal available = a.getBalance().subtract(holds);
		if (r.amount().compareTo(available) > 0) {
			throw new IllegalArgumentException("Insufficient available funds for hold");
		}

		AccountHold h = AccountHold.builder().accountId(accountId).amount(r.amount()).status(HoldStatus.ACTIVE)
				.reason(r.reason()).releaseAt(r.releaseAt()).requestFingerprint(fp).build();

		h = holdRepo.save(h);
		emitTransaction(a, "HOLD_PLACED", r.amount(), r.reason(), true, a.getBalance());

		return new HoldResponse(h.getId(), h.getAmount(), h.getStatus(), h.getCreatedAt(), h.getReleaseAt());
	}

	@Transactional(propagation = Propagation.REQUIRED)
	public HoldResponse releaseHold(UUID accountId, UUID holdId, String reason) {
		return releaseHold(accountId, holdId, reason, null);
	}

	/**
	 * Releasing is already safe to repeat: a hold that is no longer ACTIVE is
	 * returned untouched below. The key only gives the ledger entry a stable
	 * fingerprint, so a retry cannot record a second HOLD_RELEASED posting.
	 */
	@Transactional(propagation = Propagation.REQUIRED)
	public HoldResponse releaseHold(UUID accountId, UUID holdId, String reason, String idempotencyKey) {
		AccountHold h = holdRepo.findById(holdId).orElseThrow(() -> new IllegalArgumentException("Hold not found"));
		if (!h.getAccountId().equals(accountId)) {
			throw new IllegalArgumentException("Hold does not belong to this account");
		}

		// Load and authorize the account before mutating the hold, so a caller
		// who does not own the account cannot release funds held against it.
		Account a = accountRepo.findById(accountId)
				.orElseThrow(() -> new IllegalArgumentException("Account not found"));
		ensureOwnerOrAdmin(a);

		if (h.getStatus() != HoldStatus.ACTIVE) {
			return new HoldResponse(h.getId(), h.getAmount(), h.getStatus(), h.getCreatedAt(), h.getReleaseAt());
		}

		h.setStatus(HoldStatus.RELEASED);
		h.setReason(reason);
		h = holdRepo.save(h);

		emitTransaction(a, "HOLD_RELEASED", h.getAmount(), reason, true, a.getBalance(), idempotencyKey);

		return new HoldResponse(h.getId(), h.getAmount(), h.getStatus(), h.getCreatedAt(), h.getReleaseAt());
	}

	/**
	 * Takes the funds a hold reserved, in one step.
	 *
	 * <p>Replaces releasing a hold and then debiting the account. Between those
	 * two calls the funds are no longer reserved: the customer can spend them,
	 * the debit then fails for insufficient funds, and the payment can never be
	 * collected even though the reservation is gone. Capturing moves the money
	 * while it is still held, so that window does not exist.</p>
	 *
	 * <p>The amount comes from the hold, never from the caller, so what is taken
	 * cannot disagree with what was reserved.</p>
	 *
	 * <p>The ledger records one posting, a DEBIT: from the customer's point of
	 * view one thing happened, their bill was paid, and the hold mechanics
	 * behind it are not separate events on a statement.</p>
	 *
	 * <p>A hold that is no longer ACTIVE is debited without being captured. That
	 * is for the deployment in which this arrives: a payment already in flight
	 * may have had its hold released by the previous code, and refusing it would
	 * strand the payment. The debit carries the caller's key, so the money still
	 * moves exactly once.</p>
	 *
	 * @param idempotencyKey stable across retries; a repeat takes nothing further
	 */
	@Transactional(propagation = Propagation.REQUIRED)
	public HoldResponse captureHold(UUID accountId, UUID holdId, String reason, String idempotencyKey) {

		AccountHold h = holdRepo.findById(holdId)
				.orElseThrow(() -> new IllegalArgumentException("Hold not found"));

		if (!h.getAccountId().equals(accountId)) {
			throw new IllegalArgumentException("Hold does not belong to this account");
		}

		// Authorize before touching either the hold or the balance.
		Account a = accountRepo.findById(accountId)
				.orElseThrow(() -> new IllegalArgumentException("Account not found"));
		ensureOwnerOrAdmin(a);

		// A retry of a capture already applied changes nothing.
		if (alreadyPosted(accountId, idempotencyKey) || h.getStatus() == HoldStatus.CAPTURED) {
			return new HoldResponse(h.getId(), h.getAmount(), h.getStatus(), h.getCreatedAt(), h.getReleaseAt());
		}

		if (h.getStatus() == HoldStatus.ACTIVE) {
			h.setStatus(HoldStatus.CAPTURED);
			h.setReason(reason);
			h = holdRepo.save(h);
		} else {
			log.warn("Capturing hold {} on account {} while it is {}: debiting without it. "
					+ "Expected only for payments whose hold was released by an earlier release-then-debit.",
					holdId, accountId, h.getStatus());
		}

		// The funds leave the account here. Taking the amount from the hold is
		// what makes this exact: the reservation and the debit are the same
		// number by construction.
		a.setBalance(a.getBalance().subtract(h.getAmount()));
		Account saved = accountRepo.saveAndFlush(a);

		emitTransaction(saved, "DEBIT", h.getAmount(), reason, true, saved.getBalance(), idempotencyKey);

		return new HoldResponse(h.getId(), h.getAmount(), h.getStatus(), h.getCreatedAt(), h.getReleaseAt());
	}
}
