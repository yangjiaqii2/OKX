import { postParams, quantFetch } from './http';

export const quantApi = {
  stockCandidates: () => quantFetch('/stock/candidates'),
  contractCandidates: () => quantFetch('/contract/candidates'),
  contractCandles: (instId: string, bar: string) =>
    quantFetch(`/contract/candles?instId=${encodeURIComponent(instId)}&bar=${encodeURIComponent(bar)}`),
  pendingOrders: () => quantFetch('/orders/pending'),
  accountSummary: () => quantFetch('/account/summary'),
  accountBindingStatus: () => quantFetch('/account/binding-status'),
  verifyOkxAccount: () => quantFetch('/account/verify', { method: 'POST' }),
  positions: () => quantFetch('/account/positions'),
  riskStatus: () => quantFetch('/risk/status'),
  scanStocks: () => quantFetch('/stock/scan', { method: 'POST' }),
  scanContracts: () => quantFetch('/contract/scan', { method: 'POST' }),
  createTradePlan: (instId: string) =>
    quantFetch(`/contract/trade-plan?instId=${encodeURIComponent(instId)}`, {
      method: 'POST',
    }),
  createPendingOrder: (instId: string) =>
    quantFetch('/contract/pending-order', {
      method: 'POST',
      body: postParams({ instId }),
    }),
  createPendingOrderBatch: (instIds: string[]) =>
    quantFetch('/contract/pending-order-batch', {
      method: 'POST',
      body: postParams({ instIds: instIds.join(',') }),
    }),
  confirmOrder: (id: string, marginAmount: string) =>
    quantFetch(`/orders/confirm?id=${encodeURIComponent(id)}&marginAmount=${encodeURIComponent(marginAmount)}`, { method: 'POST' }),
  cancelOrder: (id: string) =>
    quantFetch(`/orders/cancel?id=${encodeURIComponent(id)}`, { method: 'POST' }),
  bindOkxAccount: (payload: { apiKey: string; secret: string; passphrase: string }) =>
    quantFetch('/account/bind', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  unbindOkxAccount: () => quantFetch('/account/unbind', { method: 'POST' }),
  emergencyStop: () => quantFetch('/system/emergency-stop', { method: 'POST' }),
  resume: () => quantFetch('/system/resume', { method: 'POST' }),
};
