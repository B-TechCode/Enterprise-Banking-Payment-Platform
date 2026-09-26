/**
 * The one place a request leaves this application.
 *
 * Every call carries the access token, and every failure arrives as an ApiError
 * with the status intact — because the services were taught to answer with a
 * status that means something (403 for a refusal, 409 for a conflict, 422 for
 * insufficient funds) and flattening that into "something went wrong" here
 * would throw away work the backend did deliberately.
 */

const BASE = import.meta.env.VITE_API_BASE_URL ?? '/api';

export class ApiError extends Error {
  constructor(status, message, body) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }

  /** True when retrying could plausibly work: the caller is not at fault. */
  get retryable() {
    return this.status === 0 || this.status >= 500;
  }
}

/** What a person should be told, by status. */
function messageFor(status, body) {
  // The services return { message } or { detail } through the shared handler.
  const fromServer = body?.message ?? body?.detail;

  switch (status) {
    case 0:
      return 'The service could not be reached. Check that the platform is running.';
    case 401:
      return 'Your session has expired. Sign in again.';
    case 403:
      return fromServer ?? 'You do not have permission to do that.';
    case 404:
      return fromServer ?? 'That could not be found.';
    case 409:
      return fromServer ?? 'That has changed since you loaded it. Try again.';
    case 422:
      return fromServer ?? 'There are not enough funds for that.';
    default:
      return status >= 500
        ? 'The service is not answering right now. You can try again.'
        : (fromServer ?? `The request was refused (${status}).`);
  }
}

/**
 * @param {string} path      beginning with a slash, e.g. /accounts/accounts/{id}
 * @param {object} options
 * @param {() => Promise<string>} options.token   supplies the access token
 * @param {string} [options.idempotencyKey]       for anything that moves money
 */
export async function request(path, { token, method = 'GET', body, idempotencyKey, signal } = {}) {
  const headers = { Accept: 'application/json' };

  if (token) {
    headers.Authorization = `Bearer ${await token()}`;
  }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (idempotencyKey) {
    // The orchestrator and AccountService both key on this: a retry after a
    // timeout settles onto the original payment rather than making a second.
    headers['Idempotency-Key'] = idempotencyKey;
  }

  let response;
  try {
    response = await fetch(`${BASE}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    });
  } catch (cause) {
    if (cause.name === 'AbortError') throw cause;
    throw new ApiError(0, messageFor(0), null);
  }

  if (response.status === 204) return null;

  const text = await response.text();
  let payload = null;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      payload = { message: text.slice(0, 200) };
    }
  }

  if (!response.ok) {
    throw new ApiError(response.status, messageFor(response.status, payload), payload);
  }

  return payload;
}

/** A key that is stable for one attempt and its retries, and unique otherwise. */
export function newIdempotencyKey(prefix = 'web') {
  return `${prefix}-${crypto.randomUUID()}`;
}
