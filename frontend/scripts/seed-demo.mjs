/**
 * Opens a demo account and puts money in it.
 *
 * A customer cannot do either: opening an account with a balance and crediting
 * one both need admin:accounts, because the platform models no deposit domain
 * and a credit cannot say where the money came from (docs/BACKLOG.md, settled
 * item 7). So seeding is an operator's job, done here rather than smuggled into
 * the application.
 *
 *   ADMIN_TOKEN=... node scripts/seed-demo.mjs <customerId> [amount]
 *
 * The token needs the admin:accounts scope and the https://mockbank/api
 * audience. It is read from the environment and never written down.
 */

const GATEWAY = process.env.GATEWAY ?? 'http://localhost:8080';
const TOKEN = process.env.ADMIN_TOKEN;
const [, , customerId, amountArg] = process.argv;
const amount = Number(amountArg ?? 5000);

if (!TOKEN || !customerId) {
  console.error(
    'usage: ADMIN_TOKEN=... node scripts/seed-demo.mjs <customerId> [amount]\n\n' +
      'The customer id is the one on the signed-in token: the overview shows it\n' +
      'when there are no accounts to list.',
  );
  process.exit(2);
}

async function call(path, { method = 'GET', body, idempotencyKey } = {}) {
  const headers = { Authorization: `Bearer ${TOKEN}`, Accept: 'application/json' };
  if (body) headers['Content-Type'] = 'application/json';
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;

  const response = await fetch(`${GATEWAY}${path}`, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });

  const text = await response.text();
  let payload = null;
  try {
    payload = text ? JSON.parse(text) : null;
  } catch {
    payload = { message: text.slice(0, 300) };
  }

  if (!response.ok) {
    const detail = payload?.message ?? payload?.detail ?? response.statusText;
    throw new Error(`${method} ${path} → ${response.status}: ${detail}`);
  }
  return payload;
}

try {
  const existing = await call(`/accounts/customer/${encodeURIComponent(customerId)}/accounts`);

  if (existing.length > 0) {
    const account = existing[0];
    console.log(`Customer already has ${existing.length} account(s); crediting ${account.id}`);

    const credited = await call(`/accounts/accounts/${account.id}/credit`, {
      method: 'POST',
      body: { amount, reason: 'demo funding' },
      // Stable per run, so re-running with the same key adds the money once.
      idempotencyKey: `demo-seed-${account.id}-${amount}`,
    });

    console.log(`Balance is now ${credited.balance} ${credited.currency}`);
  } else {
    console.log(`Opening a chequing account for ${customerId}`);

    const opened = await call('/accounts/accounts', {
      method: 'POST',
      body: {
        customerId,
        accountType: 'CHEQUING',
        accountSubType: 'PERSONAL',
        status: 'ACTIVE',
        currency: 'CAD',
        nickname: 'Everyday',
        displayName: 'Everyday Chequing',
        // Allowed only because this token is administrative.
        openingBalance: amount,
      },
      idempotencyKey: `demo-open-${customerId}`,
    });

    console.log(`Opened ${opened.id} (${opened.maskedAccountNumber}) with ${opened.balance} ${opened.currency}`);
  }

  console.log('\nReload the overview: the balance should be there.');
} catch (error) {
  console.error(`\n${error.message}`);
  if (/403/.test(error.message)) {
    console.error(
      'A 403 here means the token lacks admin:accounts, or carries a customer_id\n' +
        'that is not this customer — an ordinary customer token cannot seed.',
    );
  }
  process.exit(1);
}
