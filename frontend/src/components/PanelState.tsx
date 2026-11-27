import { Inbox } from 'lucide-react';
import type { ReactNode } from 'react';
import { t, useLanguage } from '../i18n';
import { cn } from '../lib/cn';

export function PanelState({ children, loading = false, className }: { children?: ReactNode; loading?: boolean; className?: string }) {
  useLanguage();
  return (
    <div role="status" className={cn('grid min-h-40 place-items-center px-4 py-8 text-center', className)}>
      {loading ? (
        <div className="size-5 animate-spin rounded-full border-2 border-line-strong border-t-accent" aria-label={t('加载中')} />
      ) : (
        <div className="max-w-md">
          <Inbox className="mx-auto mb-2 text-accent" size={18} aria-hidden />
          <p className="text-[13px] leading-5 text-muted">{children}</p>
        </div>
      )}
    </div>
  );
}
