import { useMemo, useSyncExternalStore } from 'react';
import { defaultI18nProvider, type I18nProvider } from 'react-admin';
import messages from './i18n.en.json';

type Language = 'zh-CN' | 'en-US';

const storageKey = 'quant-desk.language';
const listeners = new Set<() => void>();
const english: Readonly<Record<string, string>> = messages;
const adminChinese: Readonly<Record<string, string>> = {
  'ra.action.back': '返回',
  'ra.action.cancel': '取消',
  'ra.action.close': '关闭',
  'ra.action.confirm': '确认',
  'ra.action.refresh': '刷新',
  'ra.action.toggle_sidebar': '切换侧边栏',
  'ra.auth.logout': '退出登录',
  'ra.message.error': '页面出现错误。',
  'ra.message.loading': '请稍候。',
  'ra.notification.i18n_error': '语言切换失败',
  'ra.page.dashboard': '总览',
  'ra.page.error': '页面错误',
  'ra.page.loading': '正在加载',
  'ra.page.not_found': '页面未找到',
};

function readLanguage(): Language {
  try {
    return localStorage.getItem(storageKey) === 'en-US' ? 'en-US' : 'zh-CN';
  } catch {
    return 'zh-CN';
  }
}

let language = readLanguage();

function updateDocumentLanguage() {
  if (typeof document !== 'undefined') document.documentElement.lang = language;
}

updateDocumentLanguage();

export function getLanguage(): Language {
  return language;
}

export function setLanguage(next: Language) {
  if (next === language) return;
  language = next;
  try {
    localStorage.setItem(storageKey, next);
  } catch {
    // The current page can still switch languages when storage is unavailable.
  }
  updateDocumentLanguage();
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

export function useLanguage() {
  return useSyncExternalStore(subscribe, getLanguage);
}

export function t(source: string, values?: Readonly<Record<string, string | number>>): string {
  const key = source.trim();
  let message = source;
  if (language === 'en-US' && Object.prototype.hasOwnProperty.call(english, key)) {
    const offset = source.indexOf(key);
    message = `${source.slice(0, offset)}${english[key]}${source.slice(offset + key.length)}`;
  }
  return values ? message.replace(/\{([a-zA-Z]+)\}/g, (match, name: string) =>
    Object.prototype.hasOwnProperty.call(values, name) ? String(values[name]) : match) : message;
}

export function useAdminI18n(): I18nProvider {
  const locale = useLanguage();
  return useMemo(() => ({
    getLocale: () => locale,
    changeLocale: async (next: string) => {
      if (next === 'zh-CN' || next === 'en-US') setLanguage(next);
    },
    translate: (key, options) => {
      if (locale === 'zh-CN' && adminChinese[key]) return adminChinese[key];
      return key.startsWith('ra.') ? defaultI18nProvider.translate(key, options) : t(key);
    },
  }), [locale]);
}
