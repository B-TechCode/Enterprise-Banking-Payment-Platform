import { Link } from 'react-router-dom';
import { Monogram } from './icons';

/**
 * The header on public pages.
 *
 * A wordmark and one action. No navigation: a marketing page with six links
 * across the top is a page that does not know what it wants the reader to do.
 */
export default function PublicHeader({ action = null }) {
  return (
    <header className="flex items-center justify-between px-5 py-5 md:px-10 md:py-7">
      <Link to="/" className="flex items-center gap-2.5 text-ink">
        <Monogram />
        <span className="font-display text-[19px] tracking-[-0.01em]">Meridian</span>
      </Link>
      {action}
    </header>
  );
}
