import type { ReactNode } from 'react';
import { cn } from '../../lib/cn';

const tones = {
  info: 'bg-accent-soft text-accent-ink',
  success: 'bg-up-soft text-up',
  warning: 'bg-warning-soft text-warning',
  error: 'bg-down-soft text-down',
};

export function Alert({ severity = 'info', children, className }: { severity?: keyof typeof tones; children: ReactNode; className?: string }) {
  return (
    <div role="status" className={cn('rounded-lg px-3 py-2.5 text-[13px] leading-5', tones[severity], className)}>
      {children}
    </div>
  );
}
