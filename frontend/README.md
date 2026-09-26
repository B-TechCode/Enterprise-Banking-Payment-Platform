# Frontend

Customer web application for the Enterprise Banking & Payment Platform. React
(Vite), Tailwind, React Router, talking to the real services through the API
Gateway.

## Running it

```bash
npm install
cp .env.example .env.local   # then fill in VITE_AUTH0_CLIENT_ID
npm run dev                  # http://localhost:5173
```

The port is pinned. Auth0 allows exactly `http://localhost:5173/callback` as a
callback, so a fallback port would fail the check rather than start.

Calls go to `/api` and Vite proxies them to the gateway on `http://localhost:8080`,
so the backend stack needs to be running:

```bash
cd ../backend/infrastructure && docker compose up -d
```

## Seeding a demo account with money

**A customer cannot give themselves money, by design.** Crediting an account and
opening one with a balance both require `admin:accounts`, because the platform
models no deposit or funding domain — see `docs/BACKLOG.md`, settled item 7. So a
freshly provisioned customer has a zero balance and any payment they try will be
refused for insufficient funds.

Before a demo, fund the account with an administrative token:

```bash
curl -X POST http://localhost:8080/accounts/accounts/{accountId}/credit \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-seed-1" \
  -d '{"amount": 5000.00, "reason": "demo funding"}'
```

`$ADMIN_TOKEN` must carry the `admin:accounts` scope. This is a setup step on
purpose and is not worked around in the application: an app that could fund
itself would be the very thing item 7 closed.

## Auth0 tenant requirements

The SPA application must have:

- **Allowed Callback URL** `http://localhost:5173/callback`
- **Allowed Logout URL** and **Allowed Web Origin** `http://localhost:5173`
- **Authorization for the API** whose identifier is `https://mockbank/api`.
  Without it Auth0 refuses the request with *"Client … is not authorized to
  access resource server"* — the redirect is well formed and the callback is
  registered, but no token is ever issued.
- A **`customer_id` claim** on the tokens, as `https://mockbank/customer_id` or
  plain `customer_id`. Every service reads the caller's customer from it; a
  token without one can sign in and read nothing.

```bash
npm run check:auth
```

Drives a real sign-in as far as the credential prompt — no password is typed —
and checks what actually left the browser: response type, PKCE challenge and
method, audience, callback, requested scopes, and that no client secret is
present. If the tenant refuses, it prints Auth0's own words rather than a
generic failure.

## Checking the layout

```bash
npm run shots
```

Screenshots `/foundation` at 375, 768 and 1280 into `shots/`, and **fails if any
breakpoint overflows horizontally**, naming the elements responsible. It drives
the Chrome already installed on the machine, so nothing is downloaded.

Worth knowing: `chrome --headless --window-size=375,…` does *not* give a 375px
layout viewport, and screenshots taken that way look broken when the page is
fine. This script sets the viewport properly.

## Structure

```
src/
├── auth/         Auth0 wiring and the route guard
├── api/          one module per service, plus the fetch client
├── components/
│   ├── layout/   AppShell, Sidebar, icons
│   ├── money/    Amount, BalanceReveal
│   ├── data/     StatementTable, EmptyState
│   └── ui/       Button, Panel, Badge, Field
├── pages/        one per screen
└── mock/         fixtures shaped like the real DTOs, deleted as screens are wired
```

## Design

Tokens live in `src/index.css` under `@theme` — Tailwind v4 is CSS-first, so
there is no `tailwind.config.js`.

| Token | Value | Use |
|---|---|---|
| `ink` | `#0E1F33` | Sidebar, headings, body |
| `paper` | `#FBF9F6` | Page ground |
| `emerald` | `#1B6B4A` | Primary actions, credits |
| `brass` | `#B8935A` | Accent: active marker, focus ring, marked panels. Never a filled button |
| `stone` | `#8A6A57` | Borders, secondary text |
| `brick` | `#C0392B` | Debits and errors only, never decorative |

Fraunces sets headlines and the balance figure; Inter sets everything read as
data. Every monetary amount renders through `Amount`, which is tabular so
columns align, and colours by direction — emerald in, brick out, ink for a
balance.

There is one animation: the dashboard balance counts up once on mount, and not
at all for anyone who has asked their system to reduce motion.
