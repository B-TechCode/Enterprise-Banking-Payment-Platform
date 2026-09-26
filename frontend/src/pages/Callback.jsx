import { useAuth0 } from '@auth0/auth0-react';
import { Link, Navigate } from 'react-router-dom';
import PublicHeader from '../components/layout/PublicHeader';
import Button from '../components/ui/Button';

/**
 * Where Auth0 returns to.
 *
 * The SDK handles the code exchange itself; this page exists to hold the
 * moment it takes and to say something useful when it fails. Auth0's own error
 * text is shown rather than a generic apology: "Callback URL mismatch" tells
 * whoever set the tenant up exactly what to fix, and hiding it behind
 * "Something went wrong" costs an hour.
 *
 * Where to go next was put in appState at sign-in and is restored by the
 * provider's onRedirectCallback, so this page only ever sends people to the
 * overview when there was nowhere particular to return to.
 */
export default function Callback() {
  const { isLoading, isAuthenticated, error } = useAuth0();

  if (error) {
    return (
      <div className="min-h-dvh bg-paper">
        <PublicHeader />
        <main className="px-5 pt-16 md:px-10 md:pt-24">
          <div className="max-w-[46ch]">
            <h1 className="font-display text-[clamp(1.75rem,4vw,2.5rem)] leading-tight">
              Sign in did not complete
            </h1>

            <p className="mt-5 border-l-2 border-l-brick pl-4 text-[15px] leading-relaxed text-ink">
              {error.message}
            </p>

            <p className="mt-5 text-[15px] leading-relaxed text-stone">
              If this says the callback URL was rejected, the address this application was opened
              from is not one the identity provider allows. It expects{' '}
              <span className="tabular text-ink">http://localhost:5173/callback</span>.
            </p>

            <div className="mt-7">
              <Link to="/login">
                <Button variant="secondary">Back to sign in</Button>
              </Link>
            </div>
          </div>
        </main>
      </div>
    );
  }

  if (!isLoading && isAuthenticated) {
    return <Navigate to="/app" replace />;
  }

  if (!isLoading && !isAuthenticated) {
    // Reached directly, without a code to exchange.
    return <Navigate to="/login" replace />;
  }

  return (
    <div className="min-h-dvh bg-paper">
      <PublicHeader />
      <main className="px-5 pt-16 md:px-10 md:pt-24" aria-busy="true">
        <p className="text-[15px] text-stone">Completing sign in…</p>
      </main>
    </div>
  );
}
