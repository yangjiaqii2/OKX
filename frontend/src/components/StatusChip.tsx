import { formatStatus, formatTrend } from '../formatters';
import { cn } from '../lib/cn';
import { useLanguage } from '../i18n';

const tones: Record<string, string> = {
  true: 'bg-up-soft text-up',
  LOW: 'bg-up-soft text-up',
  BULLISH: 'bg-up-soft text-up',
  OKX_REAL: 'bg-up-soft text-up',
  EXECUTED: 'bg-up-soft text-up',
  false: 'bg-down-soft text-down',
  BLOCKED: 'bg-down-soft text-down',
  REJECTED: 'bg-down-soft text-down',
  FAILED: 'bg-down-soft text-down',
  BEARISH: 'bg-down-soft text-down',
  OKX_ERROR: 'bg-down-soft text-down',
  HIGH: 'bg-down-soft text-down',
  CLOSE_SUBMIT_UNKNOWN: 'bg-down-soft text-down',
  PENDING_CONFIRM: 'bg-warning-soft text-warning',
  CANCELLED: 'bg-warning-soft text-warning',
  EXPIRED: 'bg-warning-soft text-warning',
  OKX_UNBOUND: 'bg-warning-soft text-warning',
  OKX_EMPTY: 'bg-warning-soft text-warning',
  MEDIUM: 'bg-warning-soft text-warning',
  WAITING_ENTRY: 'bg-warning-soft text-warning',
  CLOSE_SUBMITTING: 'bg-warning-soft text-warning',
  CONFIRMED: 'bg-accent-soft text-accent-ink',
  SUBMITTED: 'bg-accent-soft text-accent-ink',
  CLOSE_SUBMITTED: 'bg-accent-soft text-accent-ink',
};

export function StatusChip({ value }: { value?: string | boolean | number | null }) {
  useLanguage();
  const text = String(value ?? 'UNKNOWN');
  const label = text === 'BULLISH' || text === 'BEARISH' || text === 'NEUTRAL' ? formatTrend(text) : formatStatus(text);
  return <span className={cn('inline-flex max-w-full items-center rounded-md px-1.5 py-0.5 text-[12px] font-medium leading-4', tones[text] ?? 'bg-sunken text-muted')}>{label}</span>;
}
