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

/**
 * What the service said, if it said anything.
 *
 * The shared handler answers with { error: { code, message, details } }, nested
 * one level down. Reading body.message instead — as this did at first — finds
 * nothing and falls back to a generic sentence, which hides the one piece of
 * information worth having: "Invalid Owner" and "Access Denied" are both 403s
 * and mean entirely different things.
 *
 * `details` is preferred over `message` because `message` is the status word
 * ("Forbidden") while `details` carries the reason.
 */
function serverSaid(body) {
  const nested = body?.error;
  return nested?.details ?? nested?.message ?? body?.message ?? body?.detail ?? null;
}

/** What a person should be told, by status. */
function messageFor(status, body) {
  const fromServer = serverSaid(body);

  switch (status) {
    case 0:
      return 'The service could not be reached. Check that the platform is running.';
    case 401:
      return 'Your session has expired. Sign in again.';
    case 403:
      // Two different failures arrive as 403: the token lacks the scope the
      // endpoint requires, or it carries a customer id that does not own what
      // was asked for. The service distinguishes them; so should this.
      return fromServer
        ? `Refused: ${fromServer}`
        : 'You do not have permission to do that, and the service gave no reason.';
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
