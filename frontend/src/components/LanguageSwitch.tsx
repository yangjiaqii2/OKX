import { setLanguage, useLanguage } from '../i18n';

export function LanguageSwitch({ className = '' }: { className?: string }) {
  const language = useLanguage();
  const english = language === 'en-US';
  const target = english ? 'zh-CN' : 'en-US';
  const label = english ? 'Switch to Simplified Chinese' : '切换到英文';
  return (
    <button
      type="button"
      className={`text-[13px] font-medium leading-5 text-ink ${className}`}
      aria-label={label}
      title={label}
      onClick={() => setLanguage(target)}
    >
      <span lang={target}>{english ? '简体中文' : 'English'}</span>
    </button>
  );
}
