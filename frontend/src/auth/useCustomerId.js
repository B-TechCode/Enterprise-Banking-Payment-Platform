import { useAuth0 } from '@auth0/auth0-react';
import { useEffect, useState } from 'react';
import { CUSTOMER_ID_CLAIMS } from './Auth0Config';

/**
 * The signed-in customer's id.
 *
 * Needed to ask for this customer's accounts, since that endpoint is addressed
 * by customer id. The id token is read first; if the tenant adds the claim only
 * to the access token, that is read instead.
 *
 * Reading a token in the browser decides nothing. Every endpoint checks the
 * caller against the account it is being asked about — AccountService refuses a
 * customer id that is not the caller's — so a tampered value here buys a
 * different URL and the same 403. It is used for addressing, never for
 * authorisation.
 */

function claimFrom(source) {
  if (!source) return null;
  for (const claim of CUSTOMER_ID_CLAIMS) {
    const value = source[claim];
    if (typeof value === 'string' && value.length > 0) return value;
  }
  return null;
}

/** The payload of a JWT, without verifying it: the server does that. */
function payloadOf(token) {
  try {
    const [, payload] = token.split('.');
    if (!payload) return null;
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
    return JSON.parse(json);
  } catch {
    return null;
  }
}

export default function useCustomerId() {
  const { user, isAuthenticated, getAccessTokenSilently } = useAuth0();
  const [customerId, setCustomerId] = useState(() => claimFrom(user));
  const [checked, setChecked] = useState(false);

  useEffect(() => {
    if (!isAuthenticated) {
      setCustomerId(null);
      setChecked(true);
      return;
    }

    const fromIdToken = claimFrom(user);
    if (fromIdToken) {
      setCustomerId(fromIdToken);
      setChecked(true);
      return;
    }

    let live = true;
    getAccessTokenSilently()
      .then((token) => {
        if (!live) return;
        setCustomerId(claimFrom(payloadOf(token)));
      })
      .catch(() => {
        if (live) setCustomerId(null);
      })
      .finally(() => {
        if (live) setChecked(true);
      });

    return () => {
      live = false;
    };
  }, [isAuthenticated, user, getAccessTokenSilently]);

  return {
    customerId,
    /** True once we know whether there is one, so screens can tell "still
     *  looking" from "this token carries no customer id". */
    resolved: checked,
  };
}
