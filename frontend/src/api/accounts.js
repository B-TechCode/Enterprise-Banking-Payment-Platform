import { request } from './client';

/**
 * Account Service, through the gateway.
 *
 * The doubled path segments are real: the gateway routes /accounts/** to the
 * service and rewrites the prefix to /api/v1, so this service's own
 * /api/v1/customer/{id}/accounts is reached at /accounts/customer/{id}/accounts.
 */

export function listForCustomer(customerId, options) {
  return request(`/accounts/customer/${encodeURIComponent(customerId)}/accounts`, options);
}

export function get(accountId, options) {
  return request(`/accounts/accounts/${accountId}`, options);
}

/** Balance and available (balance less active holds), which the list does not carry. */
export function balance(accountId, options) {
  return request(`/accounts/accounts/${accountId}/balance`, options);
}

export function transactions(accountId, { limit = 10, offset = 0, type, ...options } = {}) {
  const query = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  if (type) query.set('type', type);

  return request(`/accounts/accounts/${accountId}/transactions?${query}`, options);
}
