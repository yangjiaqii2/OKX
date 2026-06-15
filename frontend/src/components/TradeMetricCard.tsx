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
  success: { border: 'rgba(0, 212, 170, 0.42)', bg: 'rgba(0, 212, 170, 0.10)', glow: 'rgba(0, 212, 170, 0.20)' },
  error: { border: 'rgba(239, 68, 68, 0.46)', bg: 'rgba(239, 68, 68, 0.10)', glow: 'rgba(239, 68, 68, 0.18)' },
  warning: { border: 'rgba(245, 158, 11, 0.46)', bg: 'rgba(245, 158, 11, 0.10)', glow: 'rgba(245, 158, 11, 0.18)' },
  info: { border: 'rgba(139, 211, 255, 0.38)', bg: 'rgba(139, 211, 255, 0.09)', glow: 'rgba(139, 211, 255, 0.18)' },
  neutral: { border: 'rgba(180, 205, 255, 0.15)', bg: 'rgba(12, 18, 32, 0.62)', glow: 'rgba(180, 205, 255, 0.08)' },
};

export function TradeMetricCard({ label, value, helper, accent = 'neutral' }: TradeMetricCardProps) {
  const colors = accentMap[accent];

  return (
    <Card
      sx={{
        ...glassCard,
        bgcolor: colors.bg,
        border: `1px solid ${colors.border}`,
        minHeight: 118,
        boxShadow: `inset 0 1px 0 rgba(255,255,255,0.10), 0 20px 60px rgba(0,0,0,0.25), 0 0 48px ${colors.glow}`,
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
              fontSize: { xs: '1.25rem', md: '1.45rem' },
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
