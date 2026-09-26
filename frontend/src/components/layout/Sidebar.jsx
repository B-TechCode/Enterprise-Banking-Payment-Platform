import { NavLink } from 'react-router-dom';
import {
  AccountsIcon,
  AssistantIcon,
  DashboardIcon,
  HistoryIcon,
  Monogram,
  PaymentsIcon,
  SignOutIcon,
} from './icons';

/**
 * Navigation.
 *
 * Fixed and 240px wide on desktop, in ink so the content area keeps the paper
 * ground to itself. The active item is marked by a brass rule down its left
 * edge and a lighter ground — no filled pill, which would put a second block of
 * colour on a surface that is already dark.
 *
 * Below 768px the same five destinations become a bottom bar, because a
 * hamburger hides navigation behind a tap for no reason when there are only
 * five of them. Icon over label, thumb-reachable, and the safe-area inset is
 * respected so it clears the home indicator on a phone.
 */

const DESTINATIONS = [
  { to: '/app', label: 'Overview', Icon: DashboardIcon, end: true },
  { to: '/app/accounts', label: 'Accounts', Icon: AccountsIcon },
  { to: '/app/payments', label: 'Payments', Icon: PaymentsIcon },
  { to: '/app/assistant', label: 'Assistant', Icon: AssistantIcon },
  { to: '/app/transactions', label: 'Activity', Icon: HistoryIcon },
];

export default function Sidebar({ customerName = 'Signed in', onSignOut }) {
  return (
    <>
      {/* ---------------------------------------------------- desktop rail */}
      <nav
        aria-label="Main"
        className="fixed inset-y-0 left-0 z-20 hidden w-60 flex-col bg-ink text-paper lg:flex"
      >
        <div className="flex items-center gap-2.5 px-5 pt-6 pb-7 text-paper">
          <Monogram className="text-paper" />
          <span className="font-display text-[19px] tracking-[-0.01em]">Meridian</span>
        </div>

        <ul className="flex-1">
          {DESTINATIONS.map(({ to, label, Icon, end }) => (
            <li key={to}>
              <NavLink
                to={to}
                end={end}
                className={({ isActive }) =>
                  [
                    'relative flex items-center gap-3 py-2.5 pl-5 pr-4 text-[14px] transition-colors duration-100',
                    isActive
                      ? 'bg-ink-700 text-paper before:absolute before:inset-y-0 before:left-0 before:w-[2px] before:bg-brass'
                      : 'text-paper/70 hover:bg-ink-700/60 hover:text-paper',
                  ].join(' ')
                }
              >
                {({ isActive }) => (
                  <>
                    <Icon className={isActive ? 'text-brass' : 'text-paper/60'} />
                    {label}
                  </>
                )}
              </NavLink>
            </li>
          ))}
        </ul>

        <div className="border-t border-paper/10 px-5 py-4">
          <p className="truncate text-[13px] text-paper/60" title={customerName}>
            {customerName}
          </p>
          <button
            type="button"
            onClick={onSignOut}
            className="mt-2 inline-flex items-center gap-2 text-[13px] text-paper/70 hover:text-paper"
          >
            <SignOutIcon className="text-paper/50" />
            Sign out
          </button>
        </div>
      </nav>

      {/* ------------------------------------------------------ mobile bar */}
      <nav
        aria-label="Main"
        className="fixed inset-x-0 bottom-0 z-20 flex border-t border-ink-700 bg-ink pb-[env(safe-area-inset-bottom)] lg:hidden"
      >
        {DESTINATIONS.map(({ to, label, Icon, end }) => (
          <NavLink
            key={to}
            to={to}
            end={end}
            className={({ isActive }) =>
              [
                'relative flex flex-1 flex-col items-center gap-1 py-2.5 text-[11px]',
                isActive
                  ? 'text-paper before:absolute before:inset-x-3 before:top-0 before:h-[2px] before:bg-brass'
                  : 'text-paper/60',
              ].join(' ')
            }
          >
            {({ isActive }) => (
              <>
                <Icon className={isActive ? 'text-brass' : 'text-paper/55'} />
                {label}
              </>
            )}
          </NavLink>
        ))}
      </nav>
    </>
  );
}
