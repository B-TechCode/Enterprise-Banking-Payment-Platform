/**
 * A state, in words.
 *
 * Payment and hold states come from the backend as ACCEPTED, FUNDS_HELD,
 * POSTED, RELEASED and so on. Shouting them at the customer in the bank's own
 * vocabulary is not information, so each one is given a human label here, and
 * the raw value is kept in the title attribute for anyone debugging.
 *
 * No ALL-CAPS, no pill of saturated colour: a dot carries the state and the
 * text stays readable.
 */

const STATES = {
  ACCEPTED: { label: 'Accepted', dot: 'bg-stone' },
  FUNDS_HELD: { label: 'Funds held', dot: 'bg-brass' },
  SUBMITTED: { label: 'Submitted', dot: 'bg-brass' },
  POSTED: { label: 'Paid', dot: 'bg-emerald' },
  FAILED: { label: 'Failed', dot: 'bg-brick' },
  REJECTED: { label: 'Rejected', dot: 'bg-brick' },

  ACTIVE: { label: 'Active', dot: 'bg-emerald' },
  FROZEN: { label: 'Frozen', dot: 'bg-brass' },
  CLOSED: { label: 'Closed', dot: 'bg-stone' },

  PENDING_CONFIRMATION: { label: 'Awaiting your confirmation', dot: 'bg-brass' },
  CONFIRMED: { label: 'Confirmed', dot: 'bg-emerald' },
  EXECUTED: { label: 'Sent', dot: 'bg-emerald' },
  EXPIRED: { label: 'Expired', dot: 'bg-stone' },
};

export default function Badge({ state, className = '' }) {
  const known = STATES[state] ?? { label: state, dot: 'bg-stone' };

  return (
    <span
      className={`inline-flex items-center gap-2 text-[13px] text-ink ${className}`}
      title={state}
    >
      <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${known.dot}`} aria-hidden="true" />
      {known.label}
    </span>
  );
}
