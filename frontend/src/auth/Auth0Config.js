/**
 * Auth0 settings, read once and checked once.
 *
 * A single-page application holds no secret: the flow is Authorization Code
 * with PKCE, where the code verifier never leaves the browser and the exchange
 * cannot be replayed by anyone who intercepts the redirect. The client id below
 * is public by design.
 *
 * The audience is not optional. Without it Auth0 issues an opaque access token,
 * which the API Gateway cannot validate, and every call comes back 401 with
 * nothing in the browser to explain why.
 */

const required = {
  domain: import.meta.env.VITE_AUTH0_DOMAIN,
  clientId: import.meta.env.VITE_AUTH0_CLIENT_ID,
  audience: import.meta.env.VITE_AUTH0_AUDIENCE,
};

const missing = Object.entries(required)
  .filter(([, value]) => !value)
  .map(([key]) => key);

if (missing.length > 0) {
  // Failing here, at startup, with the names of what is missing beats failing
  // later inside a redirect where the only symptom is a blank page.
  throw new Error(
    `Auth0 is not configured: ${missing.join(', ')} missing. ` +
      'Copy .env.example to .env.local and fill it in.',
  );
}

export const auth0Config = {
  domain: required.domain,
  clientId: required.clientId,

  authorizationParams: {
    audience: required.audience,
    scope:
      import.meta.env.VITE_AUTH0_SCOPE ??
      'openid profile email fdx:accounts.read fdx:transactions.read fdx:bill.read fdx:bill.write',
    // Registered in Auth0 as an allowed callback URL. Built from the current
    // origin so a build served from anywhere else does not have to be edited,
    // but the origin still has to be registered there.
    redirect_uri: `${window.location.origin}/callback`,
  },

  /*
   * Where tokens live between page loads.
   *
   * In memory is the safer default: a token that is never written down cannot
   * be read by injected script. The cost is that every reload starts a silent
   * re-authentication in a hidden iframe, which browsers increasingly refuse
   * because the Auth0 session cookie is third-party — so reloading the page
   * would bounce the customer back to the login screen.
   *
   * Refresh tokens in local storage avoid that, at the cost of a token that
   * script running on this origin could read. The honest position: this is the
   * usual trade every SPA makes, and it holds only as long as nothing
   * untrusted runs here. Flip both lines to make the other choice.
   */
  useRefreshTokens: true,
  cacheLocation: 'localstorage',
};

/** Claim names the platform accepts, in the order CurrentUser reads them. */
export const CUSTOMER_ID_CLAIMS = ['https://mockbank/customer_id', 'customer_id'];
