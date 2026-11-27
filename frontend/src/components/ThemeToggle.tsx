import { Moon, Sun } from 'lucide-react';
import { useSyncExternalStore } from 'react';
import { t } from '../i18n';
import { readTheme, toggleTheme, type ThemeMode } from '../lib/theme';

function subscribe(listener: () => void) {
  const observer = new MutationObserver(listener);
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
  return () => observer.disconnect();
}

function getSnapshot(): ThemeMode {
  return document.documentElement.classList.contains('dark') ? 'dark' : 'light';
}

export function ThemeToggle() {
  const mode = useSyncExternalStore(subscribe, getSnapshot, readTheme);
  return (
    <button
      type="button"
      className="grid size-8 place-items-center rounded-lg text-muted hover:bg-sunken hover:text-ink"
      aria-label={t('切换外观')}
      title={mode === 'dark' ? t('浅色') : t('深色')}
      onClick={() => toggleTheme()}
    >
      {mode === 'dark' ? <Sun size={16} /> : <Moon size={16} />}
    </button>
  );
}
