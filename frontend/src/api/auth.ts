export const AUTH_TOKEN_KEY = 'quant.auth.token';
export const AUTH_USER_KEY = 'quant.auth.user';
export const AUTH_ROLE_KEY = 'quant.auth.role';

export type AuthSession = {
  authenticated?: boolean;
  token?: string;
  username?: string;
  expiresAtEpochMillis?: number;
  role?: string;
};

export function getAuthToken() {
  return window.localStorage.getItem(AUTH_TOKEN_KEY) ?? '';
}

export function setAuthSession(session: AuthSession) {
  if (!session.token) {
    clearAuthSession();
    return;
  }
  window.localStorage.setItem(AUTH_TOKEN_KEY, session.token);
  window.localStorage.setItem(AUTH_USER_KEY, session.username ?? 'admin');
  window.localStorage.setItem(AUTH_ROLE_KEY, session.role ?? '');
}

export function clearAuthSession() {
  window.localStorage.removeItem(AUTH_TOKEN_KEY);
  window.localStorage.removeItem(AUTH_USER_KEY);
  window.localStorage.removeItem(AUTH_ROLE_KEY);
}

export function getAuthUser() {
  return window.localStorage.getItem(AUTH_USER_KEY) ?? 'admin';
}

export function getAuthRole() {
  return window.localStorage.getItem(AUTH_ROLE_KEY) ?? '';
}
