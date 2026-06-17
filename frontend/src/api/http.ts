import { clearAuthSession, getAuthToken } from './auth';

function defaultApiBaseUrl() {
  return '/api/quant';
}

export const apiBaseUrl = import.meta.env.VITE_QUANT_API_URL?.trim() || defaultApiBaseUrl();

export type JsonRecord = Record<string, unknown>;

export async function quantFetch<T = unknown>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const token = getAuthToken();
  const response = await fetch(`${apiBaseUrl}${path}`, {
    ...options,
    headers: {
      Accept: 'application/json',
      ...(options.body ? { 'Content-Type': 'application/x-www-form-urlencoded' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  });

  if (!response.ok) {
    if (response.status === 401) {
      clearAuthSession();
    }
    const text = await response.text();
    let errorMessage = '';
    try {
      const errorPayload = JSON.parse(text);
      if (errorPayload && typeof errorPayload === 'object' && 'message' in errorPayload) {
        errorMessage = String(errorPayload.message);
      }
    } catch {
      errorMessage = '';
    }
    if (errorMessage) {
      throw new Error(errorMessage);
    }
    const error = new Error(text || `HTTP ${response.status}: ${response.statusText}`);
    (error as Error & { status?: number }).status = response.status;
    throw error;
  }

  const payload = await response.json();
  if (payload && typeof payload === 'object' && 'success' in payload) {
    if (payload.success === false) {
      throw new Error(String(payload.message ?? 'Request failed'));
    }
    return payload.data as T;
  }

  return payload as T;
}

export function postParams(params: Record<string, string>) {
  return new URLSearchParams(params).toString();
}

export function asArray(value: unknown): JsonRecord[] {
  if (Array.isArray(value)) {
    return value as JsonRecord[];
  }
  if (value && typeof value === 'object' && Array.isArray((value as JsonRecord).content)) {
    return (value as { content: JsonRecord[] }).content;
  }
  return [];
}

export function withId(record: JsonRecord, fallback: string | number): JsonRecord {
  return { ...record, id: record.id ?? fallback };
}
