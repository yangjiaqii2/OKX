import { colors } from '../designTokens';
import { Alert, Box, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { formatLeverage, formatStatus } from '../formatters';
import { insetPanel } from './surfaces';
import { StatusChip } from './StatusChip';
import { t, useLanguage } from '../i18n';

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
    <Stack spacing={1}>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: {
            xs: compact ? 'repeat(2, minmax(0, 1fr))' : 'repeat(6, minmax(0, 1fr))',
            sm: compact ? 'repeat(4, minmax(0, 1fr))' : 'repeat(5, minmax(0, 1fr))',
          },
          gap: 1,
          ...(!compact && {
            '& > *': { gridColumn: { xs: 'span 2', sm: 'auto' } },
            '& > :nth-of-type(-n+2)': { gridColumn: { xs: 'span 3', sm: 'auto' } },
          }),
        }}
      >
        <StripItem label={t("账户")} value={<StatusChip value={accountMode} />} />
        <StripItem label={t("风控")} value={<StatusChip value={risk.passed as boolean | undefined} />} />
        <StripItem label={t("等级")} value={formatStatus(riskLevel)} />
        <StripItem label={t("建议杠杆")} value={formatLeverage(risk.adjustedLeverage)} />
        {!compact ? <StripItem label={t("待确认")} value={pendingCount ?? t("不可用")} tone={pendingCount != null && pendingCount > 0 ? 'warning' : 'neutral'} /> : null}
      </Box>
      {showAlert ? (
        <Alert severity={risk.passed === false || accountMode === 'OKX_ERROR' ? 'error' : 'warning'}>
          {rejectReason || String(account.message ?? '') || t("当前账户或风控状态需要复核。")}
        </Alert>
      ) : null}
    </Stack>
  );
}

function StripItem({ label, value, tone = 'neutral' }: { label: string; value: ReactNode; tone?: 'warning' | 'neutral' }) {
  useLanguage();
  return (
    <Box
      sx={{
        ...insetPanel,
        p: 1,
        bgcolor: tone === 'warning' ? colors.warningSoft : colors.subtle,
        minHeight: 56,
      }}
    >
      <Typography variant="caption" color="text.secondary" fontWeight={800} sx={{ letterSpacing: 0 }}>
        {label}
      </Typography>
      <Typography component="div" fontWeight={800} sx={{ mt: 0.5, overflowWrap: 'anywhere' }}>
        {value}
      </Typography>
    </Box>
  );
}
