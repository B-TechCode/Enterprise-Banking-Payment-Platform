import { useAuth0 } from '@auth0/auth0-react';
import { Navigate, useLocation } from 'react-router-dom';

/**
 * Guards the authenticated area.
 *
 * Nothing behind this is secret — every endpoint enforces its own rules — so
 * the guard exists to keep a signed-out visitor from meeting a screenful of
 * failed requests, not to protect data.
 *
 * Where the visitor was going is remembered, so that signing in returns them
 * there rather than to the overview.
 */
export default function RequireAuth({ children }) {
  const { isAuthenticated, isLoading, error } = useAuth0();
  const location = useLocation();

  if (isLoading) {
    // Deliberately bare: a spinner here would flash on every navigation while
    // the SDK checks its cache, which is usually a few milliseconds.
    return <div className="min-h-dvh bg-paper" aria-busy="true" />;
  }

  if (error) {
    return <Navigate to="/login" replace state={{ from: location, error: error.message }} />;
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location }} />;
  }

  return children;
}
