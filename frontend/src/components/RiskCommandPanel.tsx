import ShieldIcon from '@mui/icons-material/Shield';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import { Alert, Box, Card, CardContent, LinearProgress, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { formatLeverage, formatStatus, formatUSDT } from '../formatters';
import { compactGlass, glassCard } from './glass';
import { StatusChip } from './StatusChip';

type RiskCommandPanelProps = {
  risk?: Record<string, unknown>;
  account?: Record<string, unknown>;
  pendingCount: number;
};

export function RiskCommandPanel({ risk = {}, account = {}, pendingCount }: RiskCommandPanelProps) {
  const riskLevel = String(risk.riskLevel ?? 'LOW');
  const passed = risk.passed as boolean | undefined;
  const accountMode = String(account.mode ?? 'OKX_UNBOUND');
  const rejectReason = String(risk.rejectReason ?? '');
  const createdAt = String(risk.createdAt ?? '');
  const isBlocked = passed === false || riskLevel === 'HIGH';
  const progressValue = riskLevel === 'HIGH' ? 92 : riskLevel === 'MEDIUM' ? 62 : 28;
  const progressColor = riskLevel === 'HIGH' ? 'error' : riskLevel === 'MEDIUM' ? 'warning' : 'success';

  return (
    <Card
      sx={{
        ...glassCard,
        height: '100%',
        borderColor: isBlocked ? 'rgba(239, 68, 68, 0.48)' : 'rgba(245, 158, 11, 0.36)',
        background: isBlocked ? 'rgba(127, 29, 29, 0.18)' : '#111827',
      }}
    >
      <CardContent sx={{ p: 2, '&:last-child': { pb: 2 } }}>
        <Stack spacing={1.6}>
          <Stack direction="row" justifyContent="space-between" alignItems="flex-start" gap={1.5}>
            <Stack spacing={0.5}>
              <Typography variant="caption" color="text.secondary" fontWeight={800} sx={{ textTransform: 'uppercase' }}>
                Risk Command
              </Typography>
              <Typography variant="h5" fontWeight={900} sx={{ fontFamily: '"JetBrains Mono", monospace', lineHeight: 1.1 }}>
                {formatStatus(riskLevel)}
              </Typography>
            </Stack>
            <Box
              sx={{
                width: 40,
                height: 40,
                display: 'grid',
                placeItems: 'center',
                borderRadius: 1.25,
                color: isBlocked ? 'error.main' : 'warning.main',
                bgcolor: isBlocked ? 'rgba(239, 68, 68, 0.12)' : 'rgba(245, 158, 11, 0.12)',
                border: '1px solid rgba(148, 163, 184, 0.16)',
              }}
            >
              {isBlocked ? <WarningAmberIcon /> : <ShieldIcon />}
            </Box>
          </Stack>

          <Stack spacing={0.75}>
            <Stack direction="row" justifyContent="space-between">
              <Typography variant="caption" color="text.secondary">
                风险压力
              </Typography>
              <Typography variant="caption" sx={{ fontFamily: '"JetBrains Mono", monospace' }}>
                {progressValue}%
              </Typography>
            </Stack>
            <LinearProgress variant="determinate" value={progressValue} color={progressColor} sx={{ height: 8, borderRadius: 99 }} />
          </Stack>

          <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 1 }}>
            <MiniStat label="风控" value={<StatusChip value={passed} />} />
            <MiniStat label="账户" value={<StatusChip value={accountMode} />} />
            <MiniStat label="建议杠杆" value={formatLeverage(risk.adjustedLeverage)} />
            <MiniStat label="单笔亏损" value={formatUSDT(risk.maxLossAmount)} />
            <MiniStat label="待确认" value={pendingCount} tone={pendingCount > 0 ? 'warning' : 'neutral'} />
            <MiniStat label="检查时间" value={createdAt ? createdAt.slice(11, 19) : '-'} />
          </Box>

          {rejectReason ? <Alert severity="error">{rejectReason}</Alert> : null}
          {accountMode === 'OKX_UNBOUND' ? <Alert severity="warning">OKX API 未绑定，实盘接口不可用。</Alert> : null}
          {accountMode === 'OKX_ERROR' ? <Alert severity="error">{String(account.message ?? 'OKX接口异常')}</Alert> : null}
        </Stack>
      </CardContent>
    </Card>
  );
}

function MiniStat({ label, value, tone = 'neutral' }: { label: string; value: ReactNode; tone?: 'warning' | 'neutral' }) {
  return (
    <Box
      sx={{
        ...compactGlass,
        p: 1,
        borderRadius: 1,
        bgcolor: tone === 'warning' ? 'rgba(245, 158, 11, 0.10)' : '#0f172a',
        minHeight: 58,
      }}
    >
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography component="div" fontWeight={800} sx={{ mt: 0.4, fontFamily: '"JetBrains Mono", monospace' }}>
        {value}
      </Typography>
    </Box>
  );
}
