import { useAuth0 } from '@auth0/auth0-react';
import { Navigate, useLocation } from 'react-router-dom';
import PublicHeader from '../components/layout/PublicHeader';
import Button from '../components/ui/Button';

/**
 * Sign in.
 *
 * There is no password field here, and that is the point: credentials are
 * entered on Auth0's own domain, and this application never sees them. The page
 * says so, because a bank sending someone away to type their password should
 * explain why rather than leave it looking like a redirect that went wrong.
 *
 * Same asymmetric frame as the landing page — serif anchor on the left, the
 * explanation beside it — so signing in feels like the same product.
 */
export default function Login() {
  const { loginWithRedirect, isAuthenticated, isLoading } = useAuth0();
  const location = useLocation();

  const from = location.state?.from?.pathname ?? '/app';
  const priorError = location.state?.error;

  if (isAuthenticated) {
    return <Navigate to={from} replace />;
  }

  const signIn = () =>
    loginWithRedirect({
      // Where to return to after the round trip. Read back in Callback, so a
      // visitor who asked for a payment screen lands on it rather than the
      // overview.
      appState: { returnTo: from },
    });

  return (
    <div className="min-h-dvh bg-paper">
      <PublicHeader />

      <main className="px-5 md:px-10">
        <div className="grid grid-cols-1 gap-10 pt-10 pb-24 md:grid-cols-12 md:pt-24">
          <div className="md:col-span-6">
            <h1 className="font-display text-[clamp(2.25rem,5.5vw,3.5rem)] leading-[1.05] tracking-[-0.02em]">
              Sign in to Meridian
            </h1>

            <p className="mt-5 max-w-[42ch] text-[16px] leading-relaxed text-ink">
              You will be taken to our identity provider to enter your details, and returned here
              once you have. Your password is never typed into this page.
            </p>

            {priorError && (
              <p className="mt-5 max-w-[42ch] border-l-2 border-l-brick pl-4 text-[15px] text-brick">
                {priorError}
              </p>
            )}

            <div className="mt-8">
              <Button size="lg" onClick={signIn} disabled={isLoading}>
                {isLoading ? 'Checking your session' : 'Sign in'}
              </Button>
            </div>
          </div>

          <aside className="md:col-span-5 md:col-start-8 md:pt-4">
            <div className="rule-t pt-5">
              <h2 className="text-[17px]">What happens next</h2>
              <ol className="mt-3 space-y-3 text-[15px] leading-relaxed text-stone">
                <li>
                  <span className="text-ink">Your details go to the identity provider.</span> This
                  application receives a token, never a password.
                </li>
                <li>
                  <span className="text-ink">The token names what you may do.</span> Reading
                  accounts and paying bills are separate permissions, and the services check both.
                </li>
                <li>
                  <span className="text-ink">Payments still need your confirmation.</span> Nothing
                  moves money because you signed in.
                </li>
              </ol>
            </div>
          </aside>
        </div>
      </main>
    </div>
  );
}
