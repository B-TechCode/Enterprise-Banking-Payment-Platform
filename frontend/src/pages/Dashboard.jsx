import { Link } from 'react-router-dom';
import * as accountsApi from '../api/accounts';
import { useResource } from '../api/useApi';
import useCustomerId from '../auth/useCustomerId';
import StatementTable from '../components/data/StatementTable';
import EmptyState from '../components/data/EmptyState';
import { PageHeading } from '../components/layout/AppShell';
import Amount from '../components/money/Amount';
import BalanceReveal from '../components/money/BalanceReveal';
import Badge from '../components/ui/Badge';
import Button from '../components/ui/Button';
import Panel from '../components/ui/Panel';

/**
 * The overview.
 *
 * One number is the reason anyone opens this screen, so it is the only thing
 * with size: the balance of the everyday account, in Fraunces, counting up
 * once. Everything else is subordinate — the other accounts as a short list,
 * then recent movements as a statement.
 *
 * Real data, from Account Service. Where it has nothing to show, the screen
 * says why rather than drawing an empty frame.
 */

/** The account a customer means when they say "my account". */
function everydayAccountOf(accounts) {
  return accounts.find((a) => a.accountType === 'CHEQUING' && a.status === 'ACTIVE') ?? accounts[0];
}

function Failure({ error, onRetry }) {
  return (
    <Panel className="px-5 py-5">
      <p className="text-[15px] text-ink">{error.message}</p>
      {error.retryable && (
        <div className="mt-4">
          <Button variant="secondary" onClick={onRetry}>
            Try again
          </Button>
        </div>
      )}
    </Panel>
  );
}

/** Shown while a figure is on its way, so the page does not jump when it lands. */
function Placeholder({ className = '' }) {
  return <span className={`inline-block animate-none bg-paper-shade ${className}`} aria-hidden="true" />;
}

export default function Dashboard() {
  const { customerId, resolved } = useCustomerId();

  const accounts = useResource(
    ({ token, signal }) => accountsApi.listForCustomer(customerId, { token, signal }),
    [customerId],
    Boolean(customerId),
  );

  const list = accounts.data ?? [];
  const everyday = list.length > 0 ? everydayAccountOf(list) : null;

  // Available is balance less active holds, and only the balance endpoint
  // carries it. It matters: money held for a payment in flight is not spendable,
  // and a customer who sees only the balance will believe it is.
  const funds = useResource(
    ({ token, signal }) => accountsApi.balance(everyday.id, { token, signal }),
    [everyday?.id],
    Boolean(everyday),
  );

  const activity = useResource(
    ({ token, signal }) => accountsApi.transactions(everyday.id, { limit: 6, token, signal }),
    [everyday?.id],
    Boolean(everyday),
  );

  // -------------------------------------------------------------- no customer
  if (resolved && !customerId) {
    return (
      <>
        <PageHeading title="Overview" />
        <Panel tone="marked" className="px-5 py-5">
          <h2 className="text-[17px]">This sign-in carries no customer</h2>
          <p className="mt-2 max-w-prose text-[15px] text-stone">
            The platform identifies a customer by a <span className="text-ink">customer_id</span>{' '}
            claim on the token. Without it the services cannot tell which accounts are yours, so
            there is nothing to show. The tenant adds this claim at login.
          </p>
        </Panel>
      </>
    );
  }

  return (
    <>
      <PageHeading
        title="Overview"
        supporting={everyday ? `${everyday.displayName} · ${everyday.maskedAccountNumber}` : null}
      />

      {accounts.isLoading && <Placeholder className="h-14 w-64" />}

      {accounts.status === 'failed' && <Failure error={accounts.error} onRetry={accounts.retry} />}

      {accounts.status === 'loaded' && list.length === 0 && (
        <EmptyState
          title="No accounts yet"
          supporting={
            <>
              Nothing is open under customer <span className="tabular text-ink">{customerId}</span>.
              An account is opened by an operator; a customer cannot open one with money in it.
            </>
          }
        />
      )}

      {everyday && (
        <>
          {/* ------------------------------------------------------- balance */}
          <section className="mb-10">
            {funds.status === 'loaded' ? (
              <>
                <BalanceReveal value={funds.data.balance} currency={everyday.currency} />

                <p className="mt-2 text-[14px] text-stone">
                  {Number(funds.data.totalHolds) > 0 ? (
                    <>
                      <Amount value={funds.data.available} currency={everyday.currency} size="sm" />{' '}
                      available, with{' '}
                      <Amount value={funds.data.totalHolds} currency={everyday.currency} size="sm" />{' '}
                      held for payments in flight
                    </>
                  ) : (
                    'All of it available'
                  )}
                </p>
              </>
            ) : funds.status === 'failed' ? (
              <Failure error={funds.error} onRetry={funds.retry} />
            ) : (
              <Placeholder className="h-16 w-72" />
            )}
          </section>

          {/* ------------------------------------------------------ accounts */}
          {list.length > 1 && (
            <section className="mb-10">
              <h2 className="mb-3 text-[17px]">Your accounts</h2>
              <Panel className="px-5 py-1.5">
                <ul>
                  {list.map((account) => (
                    <li
                      key={account.id}
                      className="flex items-baseline justify-between gap-4 border-b border-rule py-3 last:border-b-0"
                    >
                      <span>
                        <Link
                          to={`/app/accounts/${account.id}`}
                          className="text-[15px] text-ink underline decoration-rule-strong underline-offset-4 hover:decoration-brass"
                        >
                          {account.nickname || account.displayName}
                        </Link>
                        <span className="ml-3 text-[13px] text-stone">
                          {account.maskedAccountNumber}
                        </span>
                      </span>
                      <span className="flex items-baseline gap-5">
                        {account.status !== 'ACTIVE' && <Badge state={account.status} />}
                        <Amount value={account.balance} currency={account.currency} />
                      </span>
                    </li>
                  ))}
                </ul>
              </Panel>
            </section>
          )}

          {/* ------------------------------------------------------ activity */}
          <section>
            <div className="mb-3 flex items-baseline justify-between gap-4">
              <h2 className="text-[17px]">Recent activity</h2>
              <Link
                to="/app/transactions"
                className="text-[14px] text-ink underline decoration-rule-strong underline-offset-4 hover:decoration-brass"
              >
                Full statement
              </Link>
            </div>

            {activity.status === 'failed' ? (
              <Failure error={activity.error} onRetry={activity.retry} />
            ) : activity.status === 'loaded' && activity.data.length === 0 ? (
              <EmptyState
                title="Nothing has moved yet"
                supporting="Deposits, payments and holds will appear here as they happen."
              />
            ) : activity.status === 'loaded' ? (
              <Panel className="px-5 py-4 md:px-6 md:py-5">
                <StatementTable
                  transactions={activity.data}
                  currency={everyday.currency}
                  showBalance
                />
              </Panel>
            ) : (
              <Placeholder className="h-40 w-full" />
            )}
          </section>
        </>
      )}
    </>
  );
}
