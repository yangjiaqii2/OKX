import { postParams, quantFetch } from './http';

export const quantApi = {
  contractCandidates: () => quantFetch('/contract/candidates'),
  contractCandles: (instId: string, bar: string) =>
    quantFetch(`/contract/candles?instId=${encodeURIComponent(instId)}&bar=${encodeURIComponent(bar)}`),
  pendingOrders: () => quantFetch('/orders/pending'),
  accountSummary: () => quantFetch('/account/summary'),
  accountBindingStatus: () => quantFetch('/account/binding-status'),
  verifyOkxAccount: () => quantFetch('/account/verify', { method: 'POST' }),
  positions: () => quantFetch('/account/positions'),
  currentOkxOrders: () => quantFetch('/okx/orders/current'),
  currentOkxAlgoOrders: () => quantFetch('/okx/orders/algo'),
  closePositionRecords: (params: { page?: number; size?: number } = {}) =>
    quantFetch(`/account/positions/close-records?page=${params.page ?? 0}&size=${params.size ?? 50}`),
  autoTradeLifecycle: () => quantFetch('/auto-trade/lifecycle'),
  closePosition: (payload: { instId: string; posSide?: string; marginMode?: string }) =>
    quantFetch('/account/positions/close', {
      method: 'POST',
      body: postParams({
        instId: payload.instId,
        posSide: payload.posSide ?? '',
        marginMode: payload.marginMode ?? '',
      }),
    }),
  riskStatus: () => quantFetch('/risk/status'),
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
  systemStatus: () => quantFetch('/system/status'),
  autoTradeRecords: (params: { status?: string; instId?: string; page?: number; size?: number } = {}) => {
    const search = new URLSearchParams();
    if (params.status) search.set('status', params.status);
    if (params.instId) search.set('instId', params.instId);
    search.set('page', String(params.page ?? 0));
    search.set('size', String(params.size ?? 50));
    return quantFetch(`/auto-trade/records?${search.toString()}`);
  },
  autoTradeProfitSummary: () => quantFetch('/auto-trade/profit/summary'),
  emergencyStop: () => quantFetch('/system/emergency-stop', { method: 'POST' }),
  resume: () => quantFetch('/system/resume', { method: 'POST' }),
  enableAutoTrade: (
    marginUsdt: string,
    riskMode: 'STRICT' | 'NO_RISK' = 'STRICT',
    noRiskMinScore?: string,
    minLeverage?: string,
  ) => {
    const params: Record<string, string> = { marginUsdt, riskMode };
    if (noRiskMinScore) {
      params.noRiskMinScore = noRiskMinScore;
    }
    if (minLeverage) {
      params.minLeverage = minLeverage;
    }
    return quantFetch('/system/auto-trade/enable', {
      method: 'POST',
      body: postParams(params),
    });
  },
  disableAutoTrade: () => quantFetch('/system/auto-trade/disable', { method: 'POST' }),
};
