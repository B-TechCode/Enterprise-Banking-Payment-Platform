import { useAuth0 } from '@auth0/auth0-react';
import { Navigate, Route, Routes } from 'react-router-dom';
import RequireAuth from './auth/RequireAuth';
import AppShell, { PageHeading } from './components/layout/AppShell';
import Callback from './pages/Callback';
import Foundation from './pages/Foundation';
import Landing from './pages/Landing';
import Login from './pages/Login';

/**
 * Routes.
 *
 * Public: the landing page, sign in, and the address Auth0 returns to.
 * Everything under /app is behind the guard. Steps 3 to 7 replace the
 * placeholders below one screen at a time.
 */

function Placeholder({ title, step }) {
  return (
    <>
      <PageHeading title={title} supporting={`Built in step ${step}.`} />
      <p className="text-[15px] text-stone">
        The shell, tokens and primitives this screen is built from are on the{' '}
        <a
          className="underline decoration-rule-strong underline-offset-4 hover:decoration-brass"
          href="/foundation"
        >
          foundation
        </a>{' '}
        page.
      </p>
    </>
  );
}

/** The shell, given the signed-in customer and a way out. */
function AuthenticatedShell() {
  const { user, logout } = useAuth0();

  return (
    <AppShell
      customerName={user?.email ?? user?.name ?? 'Signed in'}
      onSignOut={() =>
        logout({ logoutParams: { returnTo: window.location.origin } })
      }
    />
  );
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Landing />} />
      <Route path="/login" element={<Login />} />
      <Route path="/callback" element={<Callback />} />

      {/* The specimen stays reachable while the screens are built, outside the
          guard so it can be looked at without signing in. */}
      <Route path="/foundation" element={<AppShell customerName="Foundation preview" />}>
        <Route index element={<Foundation />} />
      </Route>

      <Route
        path="/app"
        element={
          <RequireAuth>
            <AuthenticatedShell />
          </RequireAuth>
        }
      >
        <Route index element={<Placeholder title="Overview" step={3} />} />
        <Route path="accounts" element={<Placeholder title="Accounts" step={4} />} />
        <Route path="payments" element={<Placeholder title="Payments" step={6} />} />
        <Route path="assistant" element={<Placeholder title="Assistant" step={7} />} />
        <Route path="transactions" element={<Placeholder title="Activity" step={5} />} />
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
