import { useAuth0 } from '@auth0/auth0-react';
import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Calling the platform from a component.
 *
 * useToken hands the API modules a way to get a fresh access token without
 * every call knowing about Auth0. useResource runs one call and reports the
 * three states a screen actually has to draw: loading, failed with a reason, or
 * loaded — including loaded with nothing in it, which is not a failure and must
 * not be drawn as one.
 */

export function useToken() {
  const { getAccessTokenSilently } = useAuth0();
  return useCallback(() => getAccessTokenSilently(), [getAccessTokenSilently]);
}

/**
 * @param {(options) => Promise<any>} call  receives { token, signal }
 * @param {Array} deps                      re-runs when these change
 * @param {boolean} [ready]                 false holds the call, for when an
 *                                          input is not known yet
 */
export function useResource(call, deps, ready = true) {
  const token = useToken();
  const [state, setState] = useState({ status: 'loading', data: null, error: null });
  const [attempt, setAttempt] = useState(0);

  // Kept in a ref so that changing the callback identity on every render does
  // not re-run the request; the deps array is what decides that.
  const callRef = useRef(call);
  callRef.current = call;

  useEffect(() => {
    if (!ready) return undefined;

    const controller = new AbortController();
    let live = true;
    setState((previous) => ({ ...previous, status: 'loading' }));

    callRef
      .current({ token, signal: controller.signal })
      .then((data) => {
        if (live) setState({ status: 'loaded', data, error: null });
      })
      .catch((error) => {
        if (!live || error.name === 'AbortError') return;
        setState({ status: 'failed', data: null, error });
      });

    return () => {
      live = false;
      controller.abort();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, ready, attempt, token]);

  return {
    ...state,
    isLoading: state.status === 'loading',
    retry: () => setAttempt((n) => n + 1),
  };
}
