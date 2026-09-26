/**
 * A monetary amount.
 *
 * Every figure a customer reads goes through here, for three reasons: the
 * digits are tabular so columns align and a total does not jiggle as it
 * changes; the currency is formatted once, in one place; and colour means
 * exactly one thing. Emerald is money arriving, brick is money leaving, ink is
 * a balance — a statement where every number is coloured tells the reader
 * nothing, so plain balances stay ink.
 */

const FORMATTERS = new Map();

function formatter(currency) {
  if (!FORMATTERS.has(currency)) {
    FORMATTERS.set(
      currency,
      new Intl.NumberFormat('en-CA', {
        style: 'currency',
        currency,
        currencyDisplay: 'narrowSymbol',
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      }),
    );
  }
  return FORMATTERS.get(currency);
}

const SIZES = {
  sm: 'text-[13px]',
  base: 'text-[15px]',
  lg: 'text-xl',
  display: 'font-display text-[clamp(2.5rem,7vw,3.75rem)] leading-[1.05] tracking-[-0.02em]',
};

export default function Amount({
  value,
  currency = 'CAD',
  /** 'credit' | 'debit' | null — null means a balance, which is not a movement. */
  direction = null,
  size = 'base',
  /** Show a leading + or −. Only meaningful on a movement. */
  signed = false,
  className = '',
}) {
  const numeric = Number(value ?? 0);
  const magnitude = Math.abs(numeric);

  // A negative value is a debit whether or not the caller said so; an explicit
  // direction is for cases where the sign is not in the number, as in a
  // transaction list where every amount is stored positive and the type says
  // which way it went.
  const resolved = direction ?? (numeric < 0 ? 'debit' : null);

  const tone =
    resolved === 'credit' ? 'text-emerald' : resolved === 'debit' ? 'text-brick' : 'text-ink';

  const sign = signed && resolved === 'credit' ? '+' : signed && resolved === 'debit' ? '−' : '';

  return (
    <span
      // Never wrapped: a sign left on its own line above the figure reads as a
      // dash, and a broken amount is worse than a narrow column.
      className={`tabular whitespace-nowrap ${SIZES[size]} ${tone} ${className}`}
      // The sign is punctuation a screen reader should not spell out letter by
      // letter, and the direction is already in the row's description.
      aria-label={`${resolved === 'debit' ? 'minus ' : ''}${formatter(currency).format(magnitude)}`}
    >
      {sign}
      {formatter(currency).format(magnitude)}
    </span>
  );
}
