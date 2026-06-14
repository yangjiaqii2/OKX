import type { AuthProvider } from 'react-admin';
import { clearAuthSession, getAuthToken, getAuthUser, setAuthSession, type AuthSession } from './auth';
import { apiBaseUrl } from './http';

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
    throw new Error(text || `HTTP ${response.status}`);
  }
  return (await response.json()) as AuthSession;
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
      throw new Error('登录失败');
    }
    setAuthSession(session);
  },

  async logout() {
    try {
      await authFetch('/auth/logout', { method: 'POST' });
    } finally {
      clearAuthSession();
    }
  },

  async checkAuth() {
    if (!getAuthToken()) {
      throw new Error('需要登录');
    }
  },

  async checkError(error) {
    if (error?.status === 401 || String(error?.message ?? '').includes('401')) {
      clearAuthSession();
      throw new Error('登录已失效');
    }
  },

  async getIdentity() {
    return {
      id: getAuthUser(),
      fullName: getAuthUser(),
    };
  },

  async getPermissions() {
    return [];
  },
};
