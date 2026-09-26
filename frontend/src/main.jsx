import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Auth0Provider } from '@auth0/auth0-react';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { auth0Config } from './auth/Auth0Config';
import './index.css';

/**
 * Returning from Auth0.
 *
 * The SDK has exchanged the code by the time this runs; what is left is the
 * ?code and ?state still in the address bar. Replacing the history entry keeps
 * them out of the back button, and going through history rather than
 * window.location keeps it a client-side navigation — a full reload here would
 * throw away the session the SDK has in memory before the router could use it.
 */
function onRedirectCallback(appState) {
  window.history.replaceState({}, document.title, appState?.returnTo ?? '/app');
}

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <Auth0Provider {...auth0Config} onRedirectCallback={onRedirectCallback}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </Auth0Provider>
  </StrictMode>,
);
