import type { DataProvider, Identifier } from 'react-admin';
import { asArray, quantFetch, withId } from './http';

const listEndpoints: Record<string, string> = {
  contractCandidates: '/contract/candidates',
  pendingOrders: '/orders/pending',
  positions: '/account/positions',
  currentOkxOrders: '/okx/orders/current',
  currentOkxAlgoOrders: '/okx/orders/algo',
  closePositionRecords: '/account/positions/close-records',
  autoTradeLifecycle: '/auto-trade/lifecycle',
};

function normalize(resource: string, records: unknown) {
  return asArray(records).map((record, index) => {
    if (resource === 'contractCandidates') {
      return withId(record, String(record.instId ?? index));
    }
    if (resource === 'positions') {
      return withId(record, `${record.instId ?? 'position'}-${record.posSide ?? index}`);
    }
    if (resource === 'currentOkxOrders') {
      return withId(record, String(record.ordId ?? record.clOrdId ?? index));
    }
    if (resource === 'currentOkxAlgoOrders') {
      return withId(record, String(record.algoId ?? record.algoClOrdId ?? index));
    }
    if (resource === 'autoTradeLifecycle') {
      return withId(record, `${record.instId ?? 'lifecycle'}-${record.posSide ?? index}-${record.entryTime ?? index}`);
    }
    return withId(record, String(record.id ?? index));
  });
}

export const quantDataProvider = {
  async getList(resource, params) {
    const endpoint = listEndpoints[resource];
    if (!endpoint) {
      return { data: [], total: 0 };
    }
    const raw = await quantFetch(endpoint);
    const recordsSource = raw && typeof raw === 'object' && Array.isArray((raw as any).items)
      ? (raw as any).items
      : raw;
    const records = applyListParams(normalize(resource, recordsSource), params);
    return { data: records, total: records.length };
  },

  async getOne(resource, params) {
    if (resource === 'pendingOrders') {
      const data = await quantFetch(`/orders/detail?id=${encodeURIComponent(String(params.id))}`);
      return { data: withId(data as Record<string, unknown>, String(params.id)) };
    }
    return { data: { id: params.id } };
  },

  async getMany(resource, params) {
    const endpoint = listEndpoints[resource];
    if (!endpoint) {
      return { data: [] };
    }
    const records = normalize(resource, await quantFetch(endpoint));
    return {
      data: records.filter((record) => params.ids.includes(record.id as Identifier)),
    };
  },

  async getManyReference() {
    return { data: [], total: 0 };
  },

  async create(_resource, params) {
    return { data: { ...params.data, id: crypto.randomUUID() } };
  },

  async update(_resource, params) {
    return { data: { ...params.data, id: params.id } };
  },

  async updateMany(_resource, params) {
    return { data: params.ids };
  },

  async delete(_resource, params) {
    return { data: { id: params.id } };
  },

  async deleteMany(_resource, params) {
    return { data: params.ids };
  },
} as DataProvider;

function applyListParams(records: Record<string, unknown>[], params: any) {
  const query = String(params?.filter?.q ?? '').trim().toLowerCase();
  const filtered = query
    ? records.filter((record) =>
        Object.values(record).some((value) => String(value ?? '').toLowerCase().includes(query)),
      )
    : records;

  const field = params?.sort?.field;
  const order = params?.sort?.order === 'ASC' ? 1 : -1;
  if (!field) {
    return filtered;
  }

  return [...filtered].sort((left, right) => compareValue(left[field], right[field]) * order);
}

function compareValue(left: unknown, right: unknown) {
  const leftNumber = Number(left);
  const rightNumber = Number(right);
  if (Number.isFinite(leftNumber) && Number.isFinite(rightNumber)) {
    return leftNumber - rightNumber;
  }
  return String(left ?? '').localeCompare(String(right ?? ''), 'zh-CN');
}
