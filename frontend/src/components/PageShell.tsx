import { RefreshCw } from 'lucide-react';
import type { ReactNode } from 'react';
import { Title } from 'react-admin';
import { t, useLanguage } from '../i18n';
import { Button } from './ui/button';

type PageShellProps = {
  title: string;
  children: ReactNode;
  maxWidth?: number | string;
};

type PageHeaderProps = {
  title: string;
  subtitle?: ReactNode;
  status?: ReactNode;
  onRefresh?: () => void;
  refreshing?: boolean;
  actions?: ReactNode;
  filters?: ReactNode;
};

export function PageShell({ title, children, maxWidth }: PageShellProps) {
  return (
    <div className="min-w-0 px-4 py-6 sm:px-8 md:px-9 md:py-7">
      <Title title={title} />
      <div className="mx-auto min-w-0" style={maxWidth ? { maxWidth } : undefined}>
        {children}
      </div>
    </div>
  );
}

export function PageHeader({ title, subtitle, status, onRefresh, refreshing = false, actions, filters }: PageHeaderProps) {
  useLanguage();
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-[28px] font-semibold leading-[34px] tracking-[-0.03em]">{title}</h1>
            {status}
          </div>
          {subtitle ? <p className="mt-1 max-w-[68ch] text-[14px] leading-[22px] text-muted">{subtitle}</p> : null}
        </div>
        {actions || onRefresh ? (
          <div className="flex shrink-0 flex-wrap items-center gap-2">
            {actions}
            {onRefresh ? (
              <Button variant="ghost" onClick={onRefresh} disabled={refreshing}>
                <RefreshCw size={14} className={refreshing ? 'animate-spin' : undefined} />
                {refreshing ? t('刷新中') : t('刷新')}
              </Button>
            ) : null}
          </div>
        ) : null}
      </div>
      {filters ? <div className="flex min-w-0 flex-wrap items-center gap-2">{filters}</div> : null}
    </div>
  );
}
