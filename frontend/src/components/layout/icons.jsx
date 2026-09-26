/**
 * Navigation icons, drawn here rather than pulled from a set.
 *
 * An icon library is the fastest way to make a product look like every other
 * product. These are six paths on a 20px grid at a single stroke weight, which
 * is all the sidebar needs, and they can be tuned to sit at the same optical
 * weight as Inter at 14px — something a borrowed set never quite does.
 */

const base = {
  width: 20,
  height: 20,
  viewBox: '0 0 20 20',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.5,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
  'aria-hidden': true,
};

export function DashboardIcon(props) {
  return (
    <svg {...base} {...props}>
      <path d="M3 11.5 10 5l7 6.5" />
      <path d="M5 11v5h10v-5" />
    </svg>
  );
}

export function AccountsIcon(props) {
  return (
    <svg {...base} {...props}>
      <rect x="2.75" y="5.75" width="14.5" height="9.5" rx="1" />
      <path d="M2.75 9.25h14.5" />
      <path d="M12.5 12.75h2.25" />
    </svg>
  );
}

export function PaymentsIcon(props) {
  return (
    <svg {...base} {...props}>
      <path d="M4 16V7.5a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1V16" />
      <path d="M4 16h12" />
      <path d="M7.5 10.5h5" />
      <path d="M7.5 13h3" />
    </svg>
  );
}

export function AssistantIcon(props) {
  return (
    <svg {...base} {...props}>
      <path d="M4 5.75h12a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H8.5L5 17v-3.25H4a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1Z" />
      <path d="M7 9.5h6" />
      <path d="M7 11.75h3.5" />
    </svg>
  );
}

export function HistoryIcon(props) {
  return (
    <svg {...base} {...props}>
      <path d="M3.5 6.5h13" />
      <path d="M3.5 10h13" />
      <path d="M3.5 13.5h8.5" />
    </svg>
  );
}

export function SignOutIcon(props) {
  return (
    <svg {...base} {...props}>
      <path d="M12.5 6V4.75a1 1 0 0 0-1-1h-6a1 1 0 0 0-1 1v10.5a1 1 0 0 0 1 1h6a1 1 0 0 0 1-1V14" />
      <path d="M8.5 10h8" />
      <path d="M14.25 7.75 16.5 10l-2.25 2.25" />
    </svg>
  );
}

/** The wordmark's mark: a brass rule and an ink meridian line. */
export function Monogram({ className = '' }) {
  return (
    <svg width="22" height="22" viewBox="0 0 22 22" fill="none" aria-hidden="true" className={className}>
      <circle cx="11" cy="11" r="9" stroke="currentColor" strokeWidth="1.25" />
      <path d="M11 2v18" stroke="#B8935A" strokeWidth="1.25" />
      <path d="M4.5 7.5h13M4.5 14.5h13" stroke="currentColor" strokeWidth="1.25" opacity="0.45" />
    </svg>
  );
}
