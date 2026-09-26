import { Navigate, Route, Routes } from 'react-router-dom';
import AppShell, { PageHeading } from './components/layout/AppShell';
import Foundation from './pages/Foundation';

/**
 * Routes.
 *
 * Step 1 wires the shell and the specimen only. The public pages, real
 * authentication and the six screens arrive in steps 2 to 7; the placeholders
 * below exist so the navigation can be used and the shell judged at every
 * width, and each is replaced by its screen in turn.
 */

function Placeholder({ title, step }) {
  return (
    <>
      <PageHeading title={title} supporting={`Built in step ${step}.`} />
      <p className="text-[15px] text-stone">
        The shell, tokens and primitives this screen is built from are on the{' '}
        <a className="underline decoration-rule-strong underline-offset-4 hover:decoration-brass" href="/foundation">
          foundation
        </a>{' '}
        page.
      </p>
    </>
  );
}

export default function App() {
  return (
    <Routes>
      {/* Until step 2 brings the landing page and login, the root goes to the
          specimen rather than to a page that does not exist yet. */}
      <Route path="/" element={<Navigate to="/foundation" replace />} />

      {/* AppShell renders an Outlet, so the page is an index child rather than
          a element passed in. */}
      <Route path="/foundation" element={<AppShell customerName="Foundation preview" />}>
        <Route index element={<Foundation />} />
      </Route>

      <Route path="/app" element={<AppShell customerName="ada@example.com" />}>
        <Route index element={<Placeholder title="Overview" step={3} />} />
        <Route path="accounts" element={<Placeholder title="Accounts" step={4} />} />
        <Route path="payments" element={<Placeholder title="Payments" step={6} />} />
        <Route path="assistant" element={<Placeholder title="Assistant" step={7} />} />
        <Route path="transactions" element={<Placeholder title="Activity" step={5} />} />
      </Route>

      <Route path="*" element={<Navigate to="/foundation" replace />} />
    </Routes>
  );
}
