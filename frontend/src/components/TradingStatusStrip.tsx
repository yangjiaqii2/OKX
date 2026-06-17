import { Alert, Box, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { formatLeverage, formatStatus } from '../formatters';
import { compactGlass } from './glass';
import { StatusChip } from './StatusChip';

type TradingStatusStripProps = {
  risk?: Record<string, unknown>;
  account?: Record<string, unknown>;
  pendingCount?: number;
  compact?: boolean;
};

export function TradingStatusStrip({ risk = {}, account = {}, pendingCount = 0, compact = false }: TradingStatusStripProps) {
  const accountMode = String(account.mode ?? 'OKX_UNBOUND');
  const riskLevel = String(risk.riskLevel ?? 'LOW');
  const rejectReason = String(risk.rejectReason ?? '');
  const showAlert = accountMode === 'OKX_UNBOUND' || accountMode === 'OKX_ERROR' || risk.passed === false || Boolean(rejectReason);

  return (
    <Stack spacing={1} sx={{ mb: 2 }}>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: {
            xs: '1fr 1fr',
            md: compact ? 'repeat(4, minmax(0, 1fr))' : 'repeat(5, minmax(0, 1fr))',
          },
          gap: 0.75,
        }}
      >
        <StripItem label="账户" value={<StatusChip value={accountMode} />} />
        <StripItem label="风控" value={<StatusChip value={risk.passed as boolean | undefined} />} />
        <StripItem label="等级" value={formatStatus(riskLevel)} />
        <StripItem label="建议杠杆" value={formatLeverage(risk.adjustedLeverage)} />
        {!compact ? <StripItem label="待确认" value={pendingCount} tone={pendingCount > 0 ? 'warning' : 'neutral'} /> : null}
      </Box>
      {showAlert ? (
        <Alert severity={risk.passed === false || accountMode === 'OKX_ERROR' ? 'error' : 'warning'}>
          {rejectReason || String(account.message ?? '') || '当前账户或风控状态需要复核。'}
        </Alert>
      ) : null}
    </Stack>
  );
}

function StripItem({ label, value, tone = 'neutral' }: { label: string; value: ReactNode; tone?: 'warning' | 'neutral' }) {
  return (
    <Box
      sx={{
        ...compactGlass,
        p: 1,
        borderRadius: 1,
        bgcolor: tone === 'warning' ? 'rgba(245, 158, 11, 0.10)' : '#101826',
        minHeight: 58,
      }}
    >
      <Typography variant="caption" color="text.secondary" fontWeight={800} sx={{ letterSpacing: 0 }}>
        {label}
      </Typography>
      <Typography component="div" fontWeight={800} sx={{ mt: 0.45, fontFamily: '"JetBrains Mono", monospace', overflowWrap: 'anywhere' }}>
        {value}
      </Typography>
    </Box>
  );
}
