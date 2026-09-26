/**
 * Fixtures shaped exactly like the real API responses.
 *
 * Field names and value shapes match the backend DTOs — AccountResponse,
 * the transaction ledger rows, and the agent's ProposalSummary — so that wiring
 * a screen to the real endpoint is a change of source, not a change of markup.
 * Each screen deletes what it no longer needs; this folder should be empty by
 * the end of the build.
 */

/** AccountResponse: id, customerId, accountNumber, accountType, accountSubType,
 *  status, currency, nickname, displayName, balance, maskedAccountNumber, version */
export const accounts = [
  {
    id: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
    customerId: 'cust-demo-1',
    accountNumber: '900123456',
    accountType: 'CHEQUING',
    accountSubType: 'PERSONAL',
    status: 'ACTIVE',
    currency: 'CAD',
    nickname: 'Everyday',
    displayName: 'Everyday Chequing',
    balance: 4218.63,
    maskedAccountNumber: '*****3456',
    version: 12,
  },
  {
    id: '2b1f4a8c-33d1-4c2e-9f11-7a5b0c9d4e22',
    customerId: 'cust-demo-1',
    accountNumber: '900987654',
    accountType: 'SAVINGS',
    accountSubType: 'PERSONAL',
    status: 'ACTIVE',
    currency: 'CAD',
    nickname: 'Rainy day',
    displayName: 'Personal Savings',
    balance: 11750.0,
    maskedAccountNumber: '*****7654',
    version: 4,
  },
];

/** Ledger rows: transactionId, accountId, type, status, amount, currency,
 *  reason, balanceAfter, occurredAt */
export const transactions = [
  {
    transactionId: 'a1d2c3b4-0001-4000-8000-000000000001',
    accountId: accounts[0].id,
    type: 'DEBIT',
    status: 'POSTED',
    amount: 142.5,
    currency: 'CAD',
    reason: 'City Hydro — invoice INV-2026-118',
    balanceAfter: 4218.63,
    occurredAt: '2026-09-24T14:22:00Z',
  },
  {
    transactionId: 'a1d2c3b4-0002-4000-8000-000000000002',
    accountId: accounts[0].id,
    type: 'HOLD_RELEASED',
    status: 'POSTED',
    amount: 142.5,
    currency: 'CAD',
    reason: 'Hold released on settlement',
    balanceAfter: 4361.13,
    occurredAt: '2026-09-24T14:21:58Z',
  },
  {
    transactionId: 'a1d2c3b4-0003-4000-8000-000000000003',
    accountId: accounts[0].id,
    type: 'HOLD_PLACED',
    status: 'POSTED',
    amount: 142.5,
    currency: 'CAD',
    reason: 'Funds held for City Hydro',
    balanceAfter: 4361.13,
    occurredAt: '2026-09-23T09:02:11Z',
  },
  {
    transactionId: 'a1d2c3b4-0004-4000-8000-000000000004',
    accountId: accounts[0].id,
    type: 'CREDIT',
    status: 'POSTED',
    amount: 2640.0,
    currency: 'CAD',
    reason: 'Payroll deposit',
    balanceAfter: 4361.13,
    occurredAt: '2026-09-20T06:00:00Z',
  },
  {
    transactionId: 'a1d2c3b4-0005-4000-8000-000000000005',
    accountId: accounts[0].id,
    type: 'DEBIT',
    status: 'POSTED',
    amount: 68.99,
    currency: 'CAD',
    reason: 'Northern Telecom — invoice 55120',
    balanceAfter: 1721.13,
    occurredAt: '2026-09-18T11:40:00Z',
  },
];

/** BillerResponse, as the biller service returns it. */
export const billers = [
  { id: 'b-1', name: 'City Hydro', referenceNumber: 'HYDRO-4821', category: 'Electricity', status: 'ACTIVE' },
  { id: 'b-2', name: 'Northern Telecom', referenceNumber: 'NTEL-9930', category: 'Telecom', status: 'ACTIVE' },
  { id: 'b-3', name: 'Municipal Water', referenceNumber: 'WATER-1177', category: 'Utilities', status: 'ACTIVE' },
];

/** ProposalSummary, as POST /ai/chat returns it alongside the reply. */
export const proposal = {
  proposalId: 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
  billerName: 'City Hydro',
  maskedAccountNumber: '*****3456',
  amount: 142.5,
  currency: 'CAD',
  expiresAt: '2026-09-26T12:05:00Z',
};
