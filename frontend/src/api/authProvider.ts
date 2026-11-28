import type { AuthProvider } from 'react-admin';
import { clearAuthSession, getAuthRole, getAuthToken, getAuthUser, setAuthSession, type AuthSession } from './auth';
import { apiBaseUrl } from './http';
import { t } from '../i18n';

async function authFetch(path: string, options: RequestInit = {}) {
  const token = getAuthToken();
  const response = await fetch(`${apiBaseUrl}${path}`, {
    ...options,
    headers: {
      Accept: 'application/json',
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  });

  if (!response.ok) {
    const text = await response.text();
    let message = text || `HTTP ${response.status}`;
    try {
      const payload = JSON.parse(text);
      if (payload && typeof payload === 'object' && 'message' in payload) {
        message = String(payload.message);
      }
    } catch {
      // Keep the original response text.
    }
    throw Object.assign(new Error(message), { status: response.status });
  }
  return (await response.json()) as AuthSession;
}

export type RegistrationConfig = {
  registrationEnabled: boolean;
  inviteCodeRequired: boolean;
};

export async function fetchRegistrationConfig(): Promise<RegistrationConfig> {
  try {
    const response = await fetch(`${apiBaseUrl}/auth/registration-config`, {
      headers: { Accept: 'application/json' },
      cache: 'no-store',
    });
    if (!response.ok) {
      return { registrationEnabled: true, inviteCodeRequired: false };
    }
    return (await response.json()) as RegistrationConfig;
  } catch {
    return { registrationEnabled: true, inviteCodeRequired: false };
  }
}

export async function registerAccount(params: {
  username: string;
  password: string;
  inviteCode?: string;
}): Promise<AuthSession> {
  const session = await authFetch('/auth/register', {
    method: 'POST',
    body: JSON.stringify({
      username: params.username,
      password: params.password,
      inviteCode: params.inviteCode ?? '',
    }),
  });
  if (!session.authenticated || !session.token) {
    throw new Error(t('注册失败'));
  }
  setAuthSession(session);
  validatedSession = { token: session.token, expiresAt: session.expiresAtEpochMillis ?? Infinity };
  return session;
}

let validatedSession: { token: string; expiresAt: number } | undefined;
let sessionRequest: { token: string; promise: Promise<void> } | undefined;

async function validateSession(token: string): Promise<void> {
  let session: AuthSession | undefined;
  try {
    session = await authFetch('/auth/session', { cache: 'no-store' });
  } catch (error) {
    if (getAuthToken() !== token) return checkSession();
    const status = (error as { status?: number } | null)?.status;
    if (status !== 401 && status !== 403) throw new Error(t('登录会话校验失败'));
  }
  if (getAuthToken() !== token) return checkSession();
  if (!session?.authenticated || session.token !== token) {
    validatedSession = undefined;
    clearAuthSession();
    throw new Error(t('登录已失效'));
  }
  setAuthSession(session);
  validatedSession = { token, expiresAt: session.expiresAtEpochMillis ?? Infinity };
}

async function checkSession(): Promise<void> {
  const token = getAuthToken();
  if (!token) throw new Error(t('需要登录'));
  if (validatedSession?.token === token && validatedSession.expiresAt > Date.now()) return;
  if (sessionRequest?.token === token) return sessionRequest.promise;

  const promise = validateSession(token);
  sessionRequest = { token, promise };
  try {
    await promise;
  } finally {
    if (sessionRequest?.promise === promise) sessionRequest = undefined;
  }
}

export const authProvider: AuthProvider = {
  async login(params) {
    const session = await authFetch('/auth/login', {
      method: 'POST',
      body: JSON.stringify({
        username: params.username,
        password: params.password,
      }),
    });
    if (!session.authenticated || !session.token) {
      throw new Error(t("登录失败"));
    }
    setAuthSession(session);
  },

  async logout() {
    const token = getAuthToken();
    validatedSession = undefined;
    try {
      if (token) {
        await authFetch('/auth/logout', { method: 'POST' });
      }
    } catch {
      // Logout must clear local state even when the backend is unavailable or the session already expired.
    } finally {
      if (getAuthToken() === token) clearAuthSession();
    }
  },

  checkAuth: checkSession,

  async checkError(error) {
    if (error?.status === 401 || String(error?.message ?? '').includes('401')) {
      validatedSession = undefined;
      clearAuthSession();
      throw new Error(t("登录已失效"));
    }
  },

  async getIdentity() {
    return {
      id: getAuthUser(),
      fullName: getAuthUser(),
    };
  },

  async getPermissions() {
    return getAuthRole();
  },
};
