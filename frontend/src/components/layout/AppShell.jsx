import { Outlet } from 'react-router-dom';
import Sidebar from './Sidebar';

/**
 * The authenticated frame: navigation, and a content column on paper.
 *
 * The content column is capped at 72rem and left-aligned rather than centred in
 * the viewport, so that on a wide screen the text does not drift into the
 * middle away from the navigation. Bottom padding on small screens clears the
 * mobile nav bar.
 */
export default function AppShell({ customerName, onSignOut }) {
  return (
    <div className="min-h-dvh bg-paper">
      <Sidebar customerName={customerName} onSignOut={onSignOut} />

      <main className="px-5 pt-6 pb-24 lg:ml-60 lg:px-10 lg:pt-10 lg:pb-16">
        <div className="max-w-[72rem]">
          <Outlet />
        </div>
      </main>
    </div>
  );
}

/**
 * A page heading inside the shell.
 *
 * Serif, one size, with an optional line of supporting text. No eyebrow label
 * above it: the navigation already says where the customer is.
 */
export function PageHeading({ title, supporting, actions }) {
  return (
    <header className="mb-7 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-[clamp(1.5rem,3.2vw,2rem)] leading-tight">{title}</h1>
        {supporting && <p className="mt-1.5 max-w-prose text-[15px] text-stone">{supporting}</p>}
      </div>
      {actions}
    </header>
  );
}
