const storageKey = 'quant-desk.theme';

export type ThemeMode = 'light' | 'dark';

export function readTheme(): ThemeMode {
  try {
    return localStorage.getItem(storageKey) === 'dark' ? 'dark' : 'light';
  } catch {
    return 'light';
  }
}

export function applyTheme(mode: ThemeMode) {
  document.documentElement.classList.toggle('dark', mode === 'dark');
  try {
    localStorage.setItem(storageKey, mode);
  } catch {
    // The current view can still switch when storage is unavailable.
  }
}

export function toggleTheme() {
  const next: ThemeMode = document.documentElement.classList.contains('dark') ? 'light' : 'dark';
  applyTheme(next);
  return next;
}
