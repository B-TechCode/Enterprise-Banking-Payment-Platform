# Backlog

Known work that is deliberately not done yet. Each item says what is wrong, why it
was deferred, and what unblocks it. This is a to-do list, not a place to hide
things: an item leaves this file when it is fixed, not when it stops being
convenient.

Priorities are relative to each other, not absolute.

Item numbers are permanent IDs, referred to from pull requests and commit
messages. A fixed item is removed, and the rest keep their numbers; new items
take the next unused one. Gaps in the numbering are fixed items.

An item whose answer was a judgement rather than a patch is recorded under
**Settled** at the end, so the reasoning outlives the item.

| # | Item | Category | Priority | Blocked on |
|---|------|----------|----------|------------|
| 4 | AICommerceAgent is absent from the integration stack | Test coverage | Medium | Dummy `GEMINI_API_KEY` in the stack |
| 5 | Downstream status decoder is duplicated in two services | Housekeeping | Low | A third service needing it |
| 6 | CI actions on v4, already force-run on Node 24; runner is `ubuntu-latest` | Housekeeping | Low | Nothing — do it before 19 Oct 2026 |
| 8 | AccountService still builds its schema with `ddl-auto: update` alongside Flyway | Reliability | Medium | A full baseline migration |
| 9 | A payment's currency is never checked against the debtor account's | Correctness | Medium | Cross-service design decision |

---

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
  v4, which targets Node 20. This is past the warning stage: as of CI run #25
  (20 Sep 2026) GitHub annotates every run to say it is *forcing* checkout and
  setup-java to run on Node 24 instead, and that setup-java v4 will receive no
  further updates. The jobs still pass, but they now run on a runtime the actions
  were not released against, with no fixes coming if that breaks. The upgrade to
  v5 is mechanical; the point is to make it before a runner change makes it
  urgent.
- Both jobs run on `ubuntu-latest`, which migrates to Ubuntu 26.04 on
  **19 October 2026**. Pin `ubuntu-24.04` before then so the migration is a change
  we make deliberately rather than one that arrives as a mystery red build.

## 8. Move AccountService to `ddl-auto: validate`

**Reliability · Medium · needs a full baseline migration**

AccountService gained Flyway for the fingerprint constraints (items 1 and 2,
fixed), but Hibernate still builds its tables with `ddl-auto: update`, and
Flyway runs first. That split has three costs:

- **Every migration has to be defensive.** On a fresh database Flyway meets no
  tables at all, so each migration must check whether its tables exist and
  quietly do nothing if they don't. V1 does; every future migration has to
  remember to, and one that doesn't will fail on every new environment while
  passing on every existing one.
- **The schema has no single source.** Part of it lives in the entities, part in
  the migrations, and which part applies depends on the age of the database.
- **Drift goes unnoticed.** `update` adds whatever the entities declare and
  removes nothing, so a database can gain columns and constraints that no
  migration records.

Fix: a migration that creates the full current schema exactly as Hibernate does
wherever it is missing, with every statement guarded, since existing databases
already have it. Then `ddl-auto: validate`, so Hibernate only checks the schema
and Flyway alone changes it. The pg_dump capture in `FingerprintMigrationIT`'s resources is
a starting point, but it records the schema *before* V1 and would need V1
applied. `FingerprintMigrationIT` is the natural place to prove the baseline
matches what Hibernate expects.

Scoped to AccountService, the only service with Flyway. The other services
still run `update` without any migration tool; that's a larger decision, not
part of this item.

## 9. A payment's currency is never checked against the account it draws on

**Correctness · Medium · needs a cross-service design decision**

`BillPayValidator` checks that a payment is in the settlement currency. Nothing
checks that the payment's currency matches the **debtor account's**. A caller can
send a CAD bill payment drawn on a USD account today: it passes validation, a
hold is placed on the USD account for that number, and the debit follows. No
conversion happens anywhere, because nothing below the payment record carries a
currency at all — `CreateHoldRequest`, `HoldResponse` and `PostingRequest` are
bare amounts, and neither SettlementService nor BillPayWorkerService reads a
currency. The money moves at an implied rate of 1:1.

Reaching it takes a non-CAD account, which nothing seeds but any customer can
open: `AccountRequest.currency` is validated as `^[A-Z]{3}$` with no allowlist.

The AI agent cannot produce this. It stages in the account's own currency and now
refuses anything but the settlement currency (item 3, fixed), so it never
composes the mismatched pair. The direct API accepts it.

Two ways to close it, and the choice is the reason this is not a quick fix:

1. **Carry the currency down to the hold.** Add it to `CreateHoldRequest` so
   AccountService, which knows what the account holds, refuses a mismatch. This
   puts the check where the fact lives, and makes every future posting honest
   about its currency, but it changes a DTO several services share.
2. **Let the orchestrator compare.** It would have to read the account's
   currency first; its `AccountClient` exposes only `getOwner` and `placeHold`,
   so this means a new call or widening `AccountOwnerResponse`. Cheaper, but the
   check then sits away from the data it depends on, and holds stay
   currency-blind.

Worth settling alongside item 8: option 1 implies a ledger column, and so a
migration.

---

# Settled

## 7. Self-service credit — closed 24 Sep 2026

**Triaged as: working as intended, and a real gap against how this repository
describes itself. Both. Fixed anyway.**

`POST /accounts/{id}/credit` required `fdx:accounts.write` and admitted the
account's owner, so a customer could credit their own account any amount. The
triage asked whether that was a deliberate demo mechanism or an oversight, and
found:

- **It was the intended funding mechanism.** `PaymentLifecycleIT` funded its
  account by calling `/credit` with a customer token, and the README's business
  flow lists "account funded" as a step with the ledger reading
  `CREDIT -> HOLD_PLACED -> HOLD_RELEASED -> DEBIT`.
- **Nothing else funds an account.** No deposit, transfer, top-up or external
  rail exists anywhere in the platform.
- **Nothing could record a source even if it wanted to.** `PostingRequest` is
  `{amount, reason}`, and the ledger row has no counterparty or external
  reference field. The domain has no notion of where money comes from.
- **No service calls credit.** Not for refunds, reversals, settlement or
  interest. Its only caller was the test.
- **Restricting credit alone would have been theatre.** `openingBalance` on
  account creation let a customer open an account at any balance — the same
  fabrication in one call.

So it was not a forgotten check. It was a missing domain concept, in a platform
whose README calls itself production-oriented. Closed by making both routes
administrative rather than by inventing a deposits domain: an operator may
provision demo money, a customer may not create it. The README now says funding
is demo-only.

What a real deployment would need instead — inbound rails, a counterparty, a
reconcilable external reference — is deliberately not in this repository, and
this item is not a placeholder for building it.
