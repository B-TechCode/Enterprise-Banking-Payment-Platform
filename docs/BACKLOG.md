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
| 8 | AccountService still builds its schema with `ddl-auto: update` alongside Flyway | Reliability | Medium | A full baseline migration |
| 10 | MapStruct unmapped-target warnings, newly visible on every run | Housekeeping | Low | A judgement per mapper |
| 15 | A provisioned customer has no way to set a password | Correctness | Medium | Tenant config and a contract decision |
| 16 | The settlement currency is a literal in `BillPayValidator` | Housekeeping | Low | Nothing |
| 17 | `Transaction` silently defaults its currency to CAD | Correctness | Low | Item 8 (see entry) |
| 19 | `application-prod.yml` binds Auth0 keys the code never reads | Reliability | Medium | Nothing |
| 20 | Nothing documents which scopes the platform requires | Reliability | Medium | Nothing |

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

The baseline should also add a `currency` column to `account_hold`. Item 9 made
AccountService refuse a hold stated in a currency the account is not held in,
which needed no schema change - the account is already loaded and knows what it
holds. Recording the currency on the hold row is the remaining half, and it
belongs in the baseline rather than in a standalone migration: adding it as a
`V2` before a baseline exists means writing exactly the guarded, conditional
migration this item exists to stop.

## 10. MapStruct unmapped-target warnings

**Housekeeping · Low · not blocked**

Every run now annotates eight warnings, four per job, from three mappers:

- `AccountMapper.toEntity` leaves `id, accountNumber, balance, version,
  requestFingerprint, createdAt, updatedAt` unmapped.
- `TransactionMapper.toEntity` leaves `id, transactionId, status,
  requestFingerprint, createdAt, updatedAt, version` unmapped.
- `CustomerMapper` reports twice: `toEntity`, and `updateCustomerFromRequest`,
  which leaves `firstName, lastName, address, externalId, kycStatus, active`
  and the audit columns unmapped.

These are not new and nothing regressed. setup-java v6 added a Maven compiler
problem matcher, so javac diagnostics that always sat in the build log are now
surfaced as annotations. The warnings were true before anyone could see them.

Mostly they are correct by design: a mapper that builds an entity from a request
should not invent an id, a balance, an account number or a fingerprint - service
code and JPA set those. For those, `@Mapper(unmappedTargetPolicy =
ReportingPolicy.IGNORE)` states the intent instead of leaving a warning to be
scrolled past. No mapper sets any policy today.

One case deserves reading before it is silenced. `updateCustomerFromRequest`
takes an `UpdateCustomerRequest` of `fullName, email, phone` against a `Customer`
that also has `firstName` and `lastName`. That is probably a partial update
working as intended, but an unmapped target on an *update* is exactly the shape
of a field that silently never changes, and blanket-ignoring the policy would
bury it.

The reason to do something rather than nothing: eight warnings on every run are
eight warnings everyone learns to ignore, and the next real one arrives into
that habit.

## 15. A provisioned customer has no way to set a password

**Correctness · Medium · needs tenant config and a contract decision**

Item 13 stopped every customer sharing one known password by generating one per
user inside AuthUser and never disclosing it. That closed the exposure, and it
left a gap it did not create: the customer now has no password anyone knows, and
nothing issues them one.

The mechanism Auth0 offers is a password-change ticket -
`POST /api/v2/tickets/password-change` returns a one-time URL where the user
chooses their own password. A database connection has no "must change at next
login" flag settable when the user is created, so the ticket is the supported
route rather than one option among several.

Two things have to be decided before it can be built:

- **Delivery.** This platform cannot send email - there is no JavaMail, SMTP or
  provider dependency anywhere in it. So the ticket URL has to come back through
  the API, which changes a contract: `updateKycStatus` returns an `Integer`
  today, and `AuthServiceClient.registerCustomer` is `void`, so CustomerService
  discards the AuthUser response body entirely. A ticket URL is credential
  bearing and must not be logged on the way.
- **Tenant configuration.** The ticket endpoint, its TTL and the connection's
  password policy need checking against the actual tenant rather than assumed.

Until this is closed, a verified customer exists in the identity provider and
cannot sign in. Nothing in this repository logs a customer in, so nothing is
currently broken by that - but it is the reason item 13 is a security fix rather
than a complete feature.

## 16. The settlement currency is a literal in `BillPayValidator`

**Housekeeping · Low · not blocked**

```java
if (!"CAD".equals(r.amount().currency())) {
  throw new IllegalArgumentException("CURRENCY_NOT_ALLOWED");
}
```

The currency this platform settles in is a compile-time constant in one
validator. Supporting a second settlement currency, or running a deployment that
settles in another, means changing code rather than configuration - and the fact
is stated in exactly one place with nothing naming it as a platform-wide
decision.

Not urgent: the platform settles in CAD and nothing contradicts that. It is
filed because item 9 made the currency story explicit everywhere else - a
payment now carries its currency to the hold, and the account refuses a
mismatch - and this is the one remaining place where a currency is simply
assumed.

Worth pairing with the refusal itself: `IllegalArgumentException` becomes a 400
carrying the internal string `CURRENCY_NOT_ALLOWED`, where the account-level
mismatch now raises `CurrencyMismatchException` and answers 422 with a sentence
a caller can act on.

## 17. `Transaction` silently defaults its currency to CAD

**Correctness · Low · do after item 8**

`Transaction` carries this in its `@PrePersist`:

```java
if (currency == null) currency = "CAD";
```

Every caller sets the currency from the account (`emitTransaction` passes
`acc.getCurrency()`), so the default never fires today. That is the problem with
it: it is a fallback that would mislabel a ledger row rather than fail, and a
ledger row that names the wrong currency is worse than one that was never
written.

On a USD account, a `Transaction` built without a currency would be recorded as
CAD, and nothing downstream would question it - no service below the payment
record reads a currency at all.

Remove the default and let a missing currency fail. Sequenced after item 8
because that is when `account_hold` gains its own currency column, and the two
are the same question - whether the ledger states its currency or infers it -
answered in two places.

---

## 19. `application-prod.yml` binds Auth0 keys the code never reads

**Reliability · Medium · not blocked**

The prod profile sets:

```yaml
auth0:
  mgmt-client-id: ${AUTH0_MGMT_CLIENT_ID}
  mgmt-client-secret: ${AUTH0_MGMT_CLIENT_SECRET}
```

The code reads `${auth0.mgmt.client-id}` — a dot, not a hyphen. These two keys
bind to nothing, and the same file declares a datasource for a service that has
no datasource at all, plus `loginClientId` and friends that nothing reads.

Not currently breaking: Config Server supplies the correctly shaped keys, so the
service works in spite of this file rather than because of it. That is the
problem. Anyone reading it to find out how AuthUser is configured in production
learns something untrue, and an operator setting `AUTH0_MGMT_CLIENT_ID` for the
prod profile alone would find it silently ignored.

Found while moving the role id to configuration (item 18), in the file next to
the one being edited. Left alone deliberately: it is a separate question from
the role id, and the prod profile deserves reading as a whole rather than one
key at a time.

## 20. Nothing documents which scopes the platform requires

**Reliability · Medium · not blocked**

The scopes each endpoint demands exist only as string literals, in `@PreAuthorize`
annotations and `currentUser.hasScope(...)` calls, spread across five services.
The identity tenant that has to grant them is configured separately, by hand,
from no list. Nothing reconciles the two, and nothing fails until a request is
refused at runtime.

Not hypothetical: on 26 Sep 2026 seeding a demo account failed because the Auth0
API defined `admin:accounts.read` and `admin:accounts.write` but not plain
`admin:accounts`, which is what the credit endpoint and the opening-balance
guard actually check. The symptom was a 403 that could equally have meant a
missing scope or a customer id that owned nothing, and finding it meant grepping
the backend for scope literals and comparing them against the dashboard by eye.

What the code requires today:

| Scope | Checked by |
|---|---|
| `fdx:accounts.read` | reading accounts, balances, owner |
| `fdx:accounts.write` | opening an account, placing a hold |
| `fdx:transactions.read` | reading the ledger |
| `fdx:bill.read`, `fdx:bill.write` | reading and initiating payments |
| `admin:accounts` | crediting, a non-zero opening balance, and as an alternative on release, capture and debit |
| `admin:accounts.read` | listing every account |
| `admin:accounts.write` | changing account status |
| `admin:users.write` | AuthUser provisioning |

A table in a document drifts the first time someone adds an endpoint. Two
options that do not:

1. **Collect them at startup.** Each service logs the scopes its own controllers
   name, by reflection over the annotations. The list is then produced by the
   code that enforces it, and reading a log answers "what does this deployment
   need granted?".
2. **Fail the build on an undeclared scope.** A test walks the controllers and
   asserts every scope literal appears in a checked-in inventory file. Adding an
   endpoint with a new scope then forces a deliberate edit, and that file is the
   thing to hand whoever configures the tenant.

Option 2 is cheaper and catches the mistake earlier; option 1 is more useful
when the tenant and the deployment are managed by different people. What is not
worth doing is a hand-maintained document, since that is precisely what went
missing here.

# Settled

## 5. Duplicated downstream status decoder — closed 25 Sep 2026

**Triaged as: duplication worth keeping, with a sharper trigger for revisiting
it.**

`DownstreamStatusDecoder` exists twice, once in PaymentOrchestrator and once in
AICommerceAgent. The status-to-exception mapping is identical; the messages are
not, because one answers an API and the other answers a conversation.

Re-examined and left duplicated. What the two copies genuinely share is a
`@Configuration` holding a five-case switch — `403` to `ForbiddenException`,
`404` to `ResourceNotFoundException`, `409` to `ConflictException`, `422` to
`InsufficientFundsException`, anything else to `UpstreamException` — plus the
bean-naming workaround. Roughly fifteen lines.

What they do not share is the part that matters to whoever reads the error. All
five messages differ, and correctly: "You may not use that account for this
payment" against "That account is not yours to pay from". An extraction would
have to be parameterised by a message provider, which is a fair amount of design
to share a switch statement while the load-bearing text stays duplicated anyway.
The original hazard also still stands: a shared `@Configuration` under
`com.commons` is picked up by every service scanning that package, whether it
wants a Feign error decoder or not.

**The trigger, sharpened.** The old one — "when a third service needs it" — had
quietly gone ambiguous, because a third service needs it today (item 11).
Extract **when a third service actually adopts it**. At three real message sets
you can see what genuinely varies; at two you are guessing at the shape, and the
guess is what you would be stuck with.

**An early warning to watch.** Drift has already begun, harmlessly:
PaymentOrchestrator has a `serviceOf(methodKey)` helper, so its log line reads
`account-service answered 403 for ...`, while the agent logs the raw method key.
A logging nicety one copy gained and the other did not. If the *mapping* ever
drifts rather than the logging, that is the signal to stop deferring and
extract — two copies of a switch are cheap, two copies that disagree about what
a 409 means are not.

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

## 9. A payment's currency is never checked against the account it draws on — closed 25 Sep 2026

**Fixed at the account boundary, with no migration. Recording the currency on
the hold row is folded into item 8.**

`BillPayValidator` checked that a payment was in the settlement currency.
Nothing checked it against the **debtor account's**. A CAD payment drawn on a
USD account passed validation, placed a hold on the USD account for that number,
and was debited. No conversion happened anywhere, because nothing below the
payment record carried a currency at all - `CreateHoldRequest`, `HoldResponse`
and `PostingRequest` were bare amounts. The money moved at an implied rate of
1:1.

The backlog offered two routes and implied both were expensive. They are not.
The check needs no schema change: `createHold` already loads the account and
`a.getCurrency()` is in hand, in the same transaction, before anything is
written. A hold does not need to *store* a currency in order to *refuse* a
mismatched one.

So `CreateHoldRequest` gained a `currency`, and `createHold` refuses when it
differs. The guard sits in AccountService rather than the orchestrator for the
same reason the ownership check does: this service owns the fact, it costs no
extra query, it is atomic with the write, and every caller of the hold endpoint
is covered - not only the payment path that prompted it.

Refused with `CurrencyMismatchException`, answering **422**. Not 400, because
the request is well formed. Not 409, because nothing changed underneath and a
retry cannot succeed - and 409 already means an optimistic-lock conflict here.

Two things surfaced while closing it:

- The orchestrator's decoder mapped `422` to `InsufficientFundsException`, a
  status AccountService never emitted: insufficient funds is signalled there as
  an `IllegalArgumentException`, so it answers 400. That case was dead, and now
  carries the one meaning AccountService does answer 422 for.
- The currency guard has to precede the available-funds computation. Below it, a
  caller paying the wrong currency from an overdrawn account is told they are
  short of money - true, irrelevant, and it sends them to top up an account that
  would still refuse. A test pins the ordering; it was written only after moving
  the guard failed to break anything.

## 11. CustomerService turns AuthUser refusals into 500s — closed 25 Sep 2026

**Fixed: CustomerService now carries a downstream status decoder. The duplicate
registration case is not closed by it, and is tracked as item 12.**

`AuthServiceClient` had no error decoder, so Feign's `FeignException` reached the
shared handler's catch-all and every answer from AuthUser became
`500 INTERNAL_ERROR`. An operator marking a customer verified was told the server
had failed, which reads exactly like an outage and leaves nothing to act on.

Closed by a `DownstreamStatusDecoder` in CustomerService, the third copy of the
pattern PaymentOrchestrator and AICommerceAgent already carry, with messages
written for an operator performing a registration rather than a customer paying a
bill:

- `403` to `ForbiddenException`, `404` to `ResourceNotFoundException`, `409` to
  `ConflictException`.
- Everything else, including `401` and any 5xx, to `UpstreamException`. A
  downstream 401 means the token this service relayed was rejected, not the
  operator's own, so passing it through would ask them to re-authenticate against
  a problem they cannot fix.

`422` is deliberately absent. The other two copies map it to
`InsufficientFundsException`, which means nothing on a registration call, and a
test pins the omission so that copying the payment mapping back in has to be a
deliberate act. That divergence is the first evidence that what differs between
the three copies is more than message text — worth remembering against item 5.

**What this does not fix.** A duplicate registration still surfaces as a 500,
because AuthUser turns Auth0's 409 into its own 500 before CustomerService ever
sees a status. The decoder maps what AuthUser sends, and 409 is not among it.
Item 12 covers that, and only then does "that customer is already registered"
become reachable.

A second test pins the ordering in `updateKycStatus`: the registration call comes
before the state change, so a refusal leaves the customer `PENDING` and unsaved.
That ordering is the only thing stopping a refused registration from being
recorded as a verification, and nothing else would notice if it were reversed.

## 12. AuthUser reports every Auth0 refusal as its own 500 — closed 25 Sep 2026

**Fixed by translating at the two call sites that know what the call meant, not
by a shared handler. Auth0's 401 and 403 are deliberately not passed through.**

`Auth0UserService.createDbUser` called the Management API with a bare
`RestTemplate`. A non-2xx answer throws, and `GlobalExceptionHandler` has no
handler for any RestTemplate exception, so every refusal reached the catch-all
and came back as `500 INTERNAL_ERROR` from AuthUser itself. A duplicate user, a
rejected management token and an unreachable tenant were indistinguishable.

Three approaches were considered. A `ResponseErrorHandler` is the structural
analogue of the Feign `ErrorDecoder` used elsewhere, but it is per-`RestTemplate`,
and one template here serves both user creation and role assignment while a
second serves the token fetch - it would have had to branch on URL to know what
had failed. Adding `@ExceptionHandler(HttpStatusCodeException.class)` to the
shared handler was rejected outright: that class is scanned by every service, and
it would blanket-translate any RestTemplate call anywhere, including ones whose
downstream status means something entirely different. What shipped is a private
`translate` called from a `try`/`catch` at the specific call site.

**The mapping is not the one the Feign decoders use, and that is the point.**
Only 409 describes the caller. Auth0 answers 401 when *our* management token is
rejected and 403 when *our* M2M application lacks `create:users`. Passing either
through would blame a caller for a tenant misconfiguration they cannot see, let
alone fix, so both become 502s. AuthUser still answers a real 403 of its own when
a caller lacks `admin:users.write`, but that comes from `@PreAuthorize` above
this layer and never reaches the translation.

This is what makes item 11 mean something. CustomerService already mapped a 409
from AuthUser to "that customer is already registered in the identity provider",
but AuthUser never sent a 409, so the branch was unreachable. The full chain is
now Auth0 409 to `ConflictException` to AuthUser 409 to the CustomerService
decoder to a 409 an operator can read. A MockMvc test asserts the AuthUser half
of that hop against the response itself, because the original bug lived precisely
in the gap between raising the exception and returning a status.

Two things came with it, both approved rather than assumed:

- `CreateUserRequest` now validates its email. Auth0 answers 400 both for a
  malformed address and for a generated password its policy rejects, and those
  have opposite owners. Refusing the caller's mistake locally leaves any
  surviving 400 unambiguously ours - it is logged at error and reported as a
  502, never as the caller's bad request.
- `ManagementTokenService.getBearer` had the same bug and is fixed the same way.
  Every failure there is ours by definition, so all of them are 502s. Its log
  line carries the status only: Auth0's token error body echoes the `client_id`
  back.

The unreachable status check that followed the POST was deleted. `RestTemplate`
throws before it could ever run, so it had been the appearance of error handling
with none of the substance - which is roughly how the whole item happened.

## 13. Every customer is provisioned with the same hardcoded password — closed 25 Sep 2026

**Fixed: the password is generated per customer inside AuthUser and never
disclosed. Giving the customer a way to set their own is item 15.**

`CustomerService.updateKycStatus` built every registration with the literal
`"default-password"`, so every customer the platform had ever verified existed in
Auth0 with the same credential - and that credential was a compile-time constant
in a public repository. Knowing a customer's email was enough to sign in as them,
and rotating it would have meant a release.

It was not a placeholder. Tracing it end to end: the value reached
`POST /api/v2/users` on a `Username-Password-Authentication` connection, which
makes it usable immediately at Auth0's hosted login. Nothing in this repository
forces a reset - there is no password-change, ticket or reset flow anywhere - and
this platform has no login endpoint of its own, so that hosted page is the real
login surface. `username` was also set to the customer id, giving two usable
identifiers against the one known password.

Closed by removing the concept rather than changing the value:

- `password` is gone from `CustomerRegistrationRequest` and `CreateUserRequest`.
  A caller can no longer choose the credential a customer is created with, and
  CustomerService no longer invents one - choosing a credential was never its
  concern.
- `InitialPasswordGenerator` produces a 32-character value from a `SecureRandom`,
  seeded with one character from each class before filling so it cannot fail a
  connection policy by chance, then shuffled so the class positions are not
  fixed.
- The value is sent to Auth0 once and never logged, returned or retained. Tests
  pin all three: it is absent from the response, absent from every log line, and
  never repeated across a thousand draws.

The generated password is deliberately unusable - nobody knows it. That is the
point, and it is also why item 15 exists.

## 14. A customer could be created without the role that makes the account usable — closed 25 Sep 2026

**Provisioning is now all-or-nothing: a role that cannot be granted undoes the
user that was just created.**

The title this item carried was wrong, and worth correcting for anyone reading
back. "Silently swallowed" describes the discarded return value, but
`RestTemplate` throws on any non-2xx and `assignRole` caught nothing, so a
failure did propagate. It was not silent. It was untranslated - it escaped the
`translate` added by item 12, which wraps only the user-creation call, and
reached the catch-all as a 500.

The real fault was that `createDbUser` is two calls and could finish half way.
A customer created without a role authenticates successfully and is then refused
everything, which reads as a permissions bug rather than a provisioning one, and
nothing recorded that it had happened.

**Item 12 made that failure permanent, which is what forced the decision here.**
Once a 409 from Auth0 became a truthful "already registered", a caller retrying
after a half-failure got a correct refusal forever: the user really did exist.
The only remedy was manual surgery in the tenant. So a failed role assignment
now deletes the user it just created and reports a 502, and the retry works.

The compensation is deliberately narrow. The user was created moments earlier in
the same call and nothing can have referred to it yet, so removing it returns the
tenant to where it started; granting a role is idempotent in Auth0, so a partial
success costs nothing. If the delete also fails, the user id is logged at error -
an orphan that is named can be found and removed, and one that is not cannot.

This needs `delete:users` on the Management API application. Without that scope
the cleanup is refused and the orphan is logged instead, which is the designed
fallback rather than a new failure.

## 18. The Auth0 role ID is hardcoded in `Auth0UserService` — closed 25 Sep 2026

**Moved to configuration, and an unset value now refuses to provision rather
than provisioning without a role.**

`rol_c7PHGjx2QtuPyVBE` was a literal in source: correct in one tenant, silently
wrong in every other, and unchangeable without a release. It is now
`auth0.role-id`, from `AUTH0_CUSTOMER_ROLE_ID`, wired through the same four
places as every other Auth0 setting - the config repo, the dev fallback,
`.env.example` and the compose file.

Empty by default, matching the convention already stated there: the service
starts without it and the call fails at invocation rather than at startup. But
an empty role id must not reach Auth0 as `roles: [""]`, which comes back as an
opaque 400, so the check happens **before** the user is created. A misconfigured
deployment refuses cleanly instead of orphaning a user on every call - which
would have reintroduced item 14 by way of a deployment mistake. A test pins that
ordering.

Closed with item 14 because they are the same call: one makes a failed
assignment visible, the other makes the value it depends on correct per
environment. Either alone is half a fix.
