import Amount from '../money/Amount';

/**
 * A statement.
 *
 * Deliberately not a list of cards. A statement is read by scanning down one
 * column at a time, so the rows are dense, separated by hairlines rather than
 * gaps, and the amounts sit hard right in tabular figures. Nothing here has a
 * shadow or a corner radius, because a row is not an object to be picked up.
 *
 * Below 768px a table cannot hold four columns without either scrolling
 * sideways or shrinking the amount to illegibility, so the same rows render
 * stacked: description and amount on the first line, date and balance beneath.
 * It is the same information in the same order, not a different screen.
 */

function movementOf(transaction) {
  return transaction.type === 'CREDIT' || transaction.type === 'HOLD_RELEASED' ? 'credit' : 'debit';
}

const DATE = new Intl.DateTimeFormat('en-CA', { day: '2-digit', month: 'short' });
const DATE_FULL = new Intl.DateTimeFormat('en-CA', { day: '2-digit', month: 'short', year: 'numeric' });

function describe(transaction) {
  // The reason is what a customer recognises; the type is the bank's word for
  // it and only helps when there is no reason recorded.
  return transaction.reason?.trim() || LABELS[transaction.type] || transaction.type;
}

const LABELS = {
  CREDIT: 'Deposit',
  DEBIT: 'Payment',
  HOLD_PLACED: 'Funds held',
  HOLD_RELEASED: 'Hold released',
};

export default function StatementTable({ transactions, currency = 'CAD', showBalance = true }) {
  return (
    <div>
      {/* Column headings, sentence case. Hidden on small screens, where the
          stacked rows label themselves by position. */}
      <div className="hidden border-b border-rule-strong pb-2 text-[12px] text-stone md:grid md:grid-cols-[5.5rem_1fr_8rem_8rem] md:gap-4">
        <div>Date</div>
        <div>Description</div>
        <div className="text-right">Amount</div>
        {showBalance && <div className="text-right">Balance</div>}
      </div>

      <ul>
        {transactions.map((transaction) => {
          const direction = movementOf(transaction);
          const occurred = new Date(transaction.occurredAt);

          return (
            <li
              key={transaction.transactionId ?? transaction.id}
              className="border-b border-rule py-3 last:border-b-0 md:grid md:grid-cols-[5.5rem_1fr_8rem_8rem] md:items-baseline md:gap-4 md:py-2.5"
            >
              {/* Desktop: date column. Mobile: moves under the description. */}
              <time
                dateTime={transaction.occurredAt}
                className="hidden text-[13px] text-stone md:block"
                title={DATE_FULL.format(occurred)}
              >
                {DATE.format(occurred)}
              </time>

              <div className="flex items-baseline justify-between gap-4 md:block">
                <span className="text-[15px] text-ink">{describe(transaction)}</span>
                <span className="md:hidden">
                  <Amount value={transaction.amount} currency={currency} direction={direction} signed />
                </span>
              </div>

              <div className="mt-1 flex items-baseline justify-between gap-4 md:mt-0 md:block md:text-right">
                <time
                  dateTime={transaction.occurredAt}
                  className="text-[13px] text-stone md:hidden"
                >
                  {DATE_FULL.format(occurred)}
                </time>
                <span className="hidden md:inline">
                  <Amount value={transaction.amount} currency={currency} direction={direction} signed />
                </span>
                {showBalance && transaction.balanceAfter != null && (
                  <span className="text-[13px] text-stone md:hidden">
                    <Amount value={transaction.balanceAfter} currency={currency} size="sm" />
                  </span>
                )}
              </div>

              {showBalance && (
                <div className="hidden md:block md:text-right">
                  {transaction.balanceAfter != null ? (
                    <Amount value={transaction.balanceAfter} currency={currency} size="sm" className="text-stone" />
                  ) : (
                    <span className="text-[13px] text-stone">—</span>
                  )}
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
