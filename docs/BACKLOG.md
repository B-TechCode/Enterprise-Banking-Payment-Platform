# Backlog

Known work that is deliberately not done yet. Each item says what is wrong, why it
was deferred, and what unblocks it. This is a to-do list, not a place to hide
things: an item leaves this file when it is fixed, not when it stops being
convenient.

Priorities are relative to each other, not absolute. Nothing here is a live
exploit — the items marked security are weaknesses behind an existing control,
recorded so the control is not mistaken for the whole defence.

| # | Item | Category | Priority | Blocked on |
|---|------|----------|----------|------------|
| 1 | `account_hold.request_fingerprint` is globally unique, not per-account | Security | High | Schema migration |
| 2 | Account create fingerprint is a 32-bit `String.hashCode()` | Security | Medium | Schema migration + decision |
| 3 | Orchestrator validator is CAD-only; the agent stages any currency | Correctness | Medium | Product decision |
| 4 | AICommerceAgent is absent from the integration stack | Test coverage | Medium | Dummy `GEMINI_API_KEY` in the stack |
| 5 | Downstream status decoder is duplicated in two services | Housekeeping | Low | A third service needing it |
| 6 | CI actions on v4; runner is `ubuntu-latest` | Housekeeping | Low | Nothing — do it before 19 Oct 2026 |

---

## 1. Per-account uniqueness for hold fingerprints

**Security · High · needs a migration**

`AccountHold.requestFingerprint` carries `@Column(unique = true)`, so an
Idempotency-Key is unique across the whole table rather than within one account.

The leak this used to cause is fixed:
[`AccountService.createHold`](../backend/AccountService-develop/src/main/java/com/account/service/AccountService.java)
now looks up a replay with `findByAccountIdAndRequestFingerprint`, so a caller
reusing another customer's key is no longer handed that customer's hold id and
amount. What remains is the constraint itself. A caller whose key collides with
another account's now fails the write instead of leaking — correct, but it means
one customer can make another customer's Idempotency-Key unusable by guessing or
reusing it, and the failure reads as a database error rather than a refusal.

Fix: replace the column-level unique constraint with a composite
`UNIQUE (account_id, request_fingerprint)`, matching what `Transaction` already
does (`uk_tx_account_idem`). Deferred because the platform has no migration tool
wired up yet — the schema is Hibernate-generated — so changing a constraint needs
that decision made first.

## 2. Account create fingerprint is a weak hash

**Security · Medium · needs a migration and a decision**

`fingerprintForCreate` falls back to `Integer.toHexString(base.hashCode())` when
no Idempotency-Key is supplied: a 32-bit Java string hash over caller-supplied
fields, stored in a globally unique column.

The dangerous consequence is already closed — `create()` calls
`ensureOwnerOrAdmin` on a fingerprint match before returning the account, so a
collision can no longer hand back another customer's account and balance. What is
left is availability: a deliberate or accidental collision makes a legitimate
account creation fail, and 32 bits is small enough for that to happen by accident
at volume.

Fix: SHA-256 over the same fields plus the customer id, which both widens the hash
and scopes it. Deferred with item 1 — it rewrites stored fingerprint values, so it
needs the same migration story, and a decision on whether existing rows are
backfilled or the column is scoped per-customer instead.

## 3. CAD-only validation versus multi-currency proposals

**Correctness · Medium · needs a product decision**

`BillPayValidator` rejects any currency but CAD with `CURRENCY_NOT_ALLOWED`. The
AI agent stages a proposal in whatever currency the debtor account holds, and does
not check it against that rule. A customer with a USD account can therefore be
shown a proposal that reads as ready to confirm, and have it refused at
confirmation time.

Since the agent gained downstream-status translation the refusal at least reports
honestly rather than as a server error, so this is a poor experience rather than a
wrong outcome. Two defensible fixes, and the choice is a product one: refuse at
staging so the customer is told immediately, or widen the validator if the
platform is meant to settle more than CAD.

## 4. AICommerceAgent is not in the integration stack

**Test coverage · Medium**

`PaymentLifecycleIT` boots Postgres, Kafka, Config Server and five services, but
not the agent. The propose-then-confirm path — including the refusal translation
and the `noRollbackFor` behaviour that protects against a double charge on retry —
is covered by unit tests only. The rollback exemption in particular cannot be
proven by a unit test: with a mocked repository there is no transaction to roll
back, which is why `PaymentConfirmationRollbackTest` asserts the annotation rather
than the behaviour.

Blocked on a small piece of plumbing: the agent fails fast at startup without
`GEMINI_API_KEY`, so the stack needs a dummy value injected. No live model call is
wanted or needed — the confirm endpoint does not involve the model at all.

## 5. Duplicated downstream status decoder

**Housekeeping · Low · deliberate for now**

`DownstreamStatusDecoder` exists twice, once in PaymentOrchestrator and once in
AICommerceAgent. The status-to-exception mapping is identical; the messages are
not, because one answers an API and the other answers a conversation.

Left duplicated on purpose. A shared `@Configuration` under `com.commons` would be
picked up by every service that scans that package, whether or not it wants the
behaviour, and two copies is not yet enough duplication to justify designing that
away. Worth extracting to commons-security — as a class that each service opts
into, not an auto-registered bean — when a third service needs it.

## 6. CI housekeeping

**Housekeeping · Low · not blocked**

Two small things in [`ci.yml`](../.github/workflows/ci.yml):

- `actions/checkout`, `actions/setup-java` and `actions/upload-artifact` are all on
  v4. v5 is current; the upgrade is mechanical.
- Both jobs run on `ubuntu-latest`, which migrates to Ubuntu 26.04 on
  **19 October 2026**. Pin `ubuntu-24.04` before then so the migration is a change
  we make deliberately rather than one that arrives as a mystery red build.
