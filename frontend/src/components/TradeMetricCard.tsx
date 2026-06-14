import { Card, CardContent, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';

type TradeMetricCardProps = {
  label: string;
  value: ReactNode;
  helper?: ReactNode;
  accent?: 'success' | 'error' | 'warning' | 'info' | 'neutral';
};

const accentMap = {
  success: { border: 'rgba(0, 212, 170, 0.35)', bg: 'rgba(0, 212, 170, 0.08)' },
  error: { border: 'rgba(239, 68, 68, 0.38)', bg: 'rgba(239, 68, 68, 0.08)' },
  warning: { border: 'rgba(245, 158, 11, 0.38)', bg: 'rgba(245, 158, 11, 0.08)' },
  info: { border: 'rgba(59, 130, 246, 0.35)', bg: 'rgba(59, 130, 246, 0.08)' },
  neutral: { border: 'rgba(148, 163, 184, 0.14)', bg: 'rgba(15, 23, 42, 0.72)' },
};

export function TradeMetricCard({ label, value, helper, accent = 'neutral' }: TradeMetricCardProps) {
  const colors = accentMap[accent];

  return (
    <Card
      sx={{
        bgcolor: colors.bg,
        border: `1px solid ${colors.border}`,
        minHeight: 106,
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
              fontSize: '1.35rem',
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
