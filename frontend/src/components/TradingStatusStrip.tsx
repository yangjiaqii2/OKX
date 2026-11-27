import type { ReactNode } from 'react';
import { formatLeverage, formatStatus } from '../formatters';
import { t, useLanguage } from '../i18n';
import { Alert } from './ui/alert';
import { StatusChip } from './StatusChip';

type TradingStatusStripProps = {
  risk?: Record<string, unknown>;
  account?: Record<string, unknown>;
  pendingCount?: number | null;
  compact?: boolean;
};

export function TradingStatusStrip({ risk = {}, account = {}, pendingCount = 0, compact = false }: TradingStatusStripProps) {
  useLanguage();
  const accountMode = String(account.mode ?? 'UNKNOWN');
  const riskLevel = String(risk.riskLevel ?? 'UNKNOWN');
  const rejectReason = String(risk.rejectReason ?? '');
  const showAlert = accountMode === 'OKX_UNBOUND' || accountMode === 'OKX_ERROR' || risk.passed === false || Boolean(rejectReason);

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-x-5 gap-y-2 text-[13px] leading-5 text-muted">
        <Item label={t('账户')} value={<StatusChip value={accountMode} />} />
        <Item label={t('风控')} value={<StatusChip value={risk.passed as boolean | undefined} />} />
        <Item label={t('等级')} value={formatStatus(riskLevel)} />
        <Item label={t('建议杠杆')} value={formatLeverage(risk.adjustedLeverage)} />
        {compact ? null : <Item label={t('待确认')} value={pendingCount ?? t('不可用')} />}
      </div>
      {showAlert ? (
        <Alert severity={risk.passed === false || accountMode === 'OKX_ERROR' ? 'error' : 'warning'}>
          {rejectReason || String(account.message ?? '') || t('当前账户或风控状态需要复核。')}
        </Alert>
      ) : null}
    </div>
  );
}

function Item({ label, value }: { label: string; value: ReactNode }) {
  return <span>{label} <b className="font-medium text-ink">{value}</b></span>;
}
