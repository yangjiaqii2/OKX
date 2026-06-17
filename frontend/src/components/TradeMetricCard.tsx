import { Card, CardContent, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { glassCard } from './glass';

type TradeMetricCardProps = {
  label: string;
  value: ReactNode;
  helper?: ReactNode;
  accent?: 'success' | 'error' | 'warning' | 'info' | 'neutral';
};

const accentMap = {
  success: { border: 'rgba(34, 197, 94, 0.42)', bg: 'rgba(34, 197, 94, 0.09)', stripe: '#22c55e' },
  error: { border: 'rgba(239, 68, 68, 0.46)', bg: 'rgba(239, 68, 68, 0.09)', stripe: '#ef4444' },
  warning: { border: 'rgba(245, 158, 11, 0.46)', bg: 'rgba(245, 158, 11, 0.09)', stripe: '#f59e0b' },
  info: { border: 'rgba(56, 189, 248, 0.38)', bg: 'rgba(56, 189, 248, 0.08)', stripe: '#38bdf8' },
  neutral: { border: 'rgba(148, 163, 184, 0.16)', bg: '#111827', stripe: '#64748b' },
};

export function TradeMetricCard({ label, value, helper, accent = 'neutral' }: TradeMetricCardProps) {
  const colors = accentMap[accent];

  return (
    <Card
      sx={{
        ...glassCard,
        bgcolor: colors.bg,
        border: `1px solid ${colors.border}`,
        minHeight: 112,
        boxShadow: '0 16px 42px rgba(0,0,0,0.20)',
        '&::before': {
          content: '""',
          position: 'absolute',
          left: 0,
          top: 0,
          bottom: 0,
          width: 3,
          bgcolor: colors.stripe,
        },
      }}
    >
      <CardContent sx={{ p: 2, '&:last-child': { pb: 2 } }}>
        <Stack spacing={0.75} minWidth={0}>
          <Typography
            variant="caption"
            color="text.secondary"
            fontWeight={800}
            sx={{ textTransform: 'uppercase', fontSize: '0.68rem' }}
          >
            {label}
          </Typography>
          <Typography
            component="div"
            fontWeight={800}
            sx={{
              fontFamily: '"JetBrains Mono", "SF Mono", monospace',
              fontSize: { xs: '1.15rem', md: '1.32rem' },
              lineHeight: 1.2,
              overflowWrap: 'anywhere',
            }}
          >
            {value}
          </Typography>
          {helper ? (
            <Typography variant="caption" color="text.secondary" sx={{ overflowWrap: 'anywhere' }}>
              {helper}
            </Typography>
          ) : null}
        </Stack>
      </CardContent>
    </Card>
  );
}
