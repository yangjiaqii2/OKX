import { postParams, quantFetch } from './http';

export const quantApi = {
  contractCandidates: () => quantFetch('/contract/candidates'),
  aiContractSelection: () => quantFetch('/contract/ai-selection'),
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
  autoTradeEvents: (limit = 50) => quantFetch(`/auto-trade/events?limit=${encodeURIComponent(String(limit))}`),
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
  llmProfileStatus: () => quantFetch('/llm/profiles/status'),
  llmProfiles: () => quantFetch('/llm/profiles'),
  saveLlmProfile: (payload: {
    id?: number | null;
    name: string;
    baseUrl: string;
    modelName: string;
    apiKey?: string;
    active?: boolean;
  }) =>
    quantFetch('/llm/profiles', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  activateLlmProfile: (id: number) =>
    quantFetch(`/llm/profiles/${encodeURIComponent(String(id))}/activate`, { method: 'POST' }),
  testLlmProfile: (id: number) =>
    quantFetch(`/llm/profiles/${encodeURIComponent(String(id))}/test`, { method: 'POST' }),
  deleteLlmProfile: (id: number) =>
    quantFetch(`/llm/profiles/${encodeURIComponent(String(id))}`, { method: 'DELETE' }),
  agentTemplates: () => quantFetch('/agents/templates'),
  agents: () => quantFetch('/agents'),
  saveAgent: (payload: Record<string, unknown>) =>
    quantFetch('/agents', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  startAgent: (id: number) =>
    quantFetch(`/agents/${encodeURIComponent(String(id))}/start`, { method: 'POST' }),
  pauseAgent: (id: number) =>
    quantFetch(`/agents/${encodeURIComponent(String(id))}/pause`, { method: 'POST' }),
  runAgentOnce: (id: number) =>
    quantFetch(`/agents/${encodeURIComponent(String(id))}/run-once`, { method: 'POST' }),
  agentRuns: (id: number, limit = 20) =>
    quantFetch(`/agents/${encodeURIComponent(String(id))}/runs?limit=${encodeURIComponent(String(limit))}`),
  deleteAgent: (id: number) =>
    quantFetch(`/agents/${encodeURIComponent(String(id))}`, { method: 'DELETE' }),
  agentWsStatus: () => quantFetch('/agents/ws-status'),
  agentMemories: (id: number) => quantFetch(`/agents/${encodeURIComponent(String(id))}/memories`),
  addAgentMemory: (id: number, payload: { memoryType: string; instId?: string; content: string; pinned?: boolean }) =>
    quantFetch(`/agents/${encodeURIComponent(String(id))}/memories`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }),
  togglePinAgentMemory: (id: number, memoryId: number) =>
    quantFetch(`/agents/${encodeURIComponent(String(id))}/memories/${encodeURIComponent(String(memoryId))}/pin`, { method: 'POST' }),
  deleteAgentMemory: (id: number, memoryId: number) =>
    quantFetch(`/agents/${encodeURIComponent(String(id))}/memories/${encodeURIComponent(String(memoryId))}`, { method: 'DELETE' }),
  reflectAgent: (id: number) =>
    quantFetch(`/agents/${encodeURIComponent(String(id))}/reflect`, { method: 'POST' }),
  adminAgentOverview: () => quantFetch('/agents/admin/overview'),
  adminPauseAllAgents: () => quantFetch('/agents/admin/pause-all', { method: 'POST' }),
  systemStatus: () => quantFetch('/system/status'),
  runtimeStatus: (signal?: AbortSignal) => quantFetch('/system/runtime-status', { signal }),
  fxRate: (base = 'USD', quote = 'CNY') =>
    quantFetch(`/system/fx-rate?base=${encodeURIComponent(base)}&quote=${encodeURIComponent(quote)}`),
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
    budgetValue: string,
    riskMode: 'STRICT' | 'NO_RISK' = 'STRICT',
    noRiskMinScore?: string,
    minLeverage?: string,
    budgetMode: 'FIXED_USDT' | 'AVAILABLE_BALANCE_PERCENT' = 'FIXED_USDT',
  ) => {
    const params: Record<string, string> = {
      riskMode,
      budgetMode,
      ...(budgetMode === 'AVAILABLE_BALANCE_PERCENT'
        ? { marginPct: budgetValue }
        : { marginUsdt: budgetValue }),
    };
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
