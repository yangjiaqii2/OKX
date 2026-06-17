import { Card, CardContent, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { glassCard, interactiveGlass } from './glass';

type MetricCardProps = {
  label: string;
  value: ReactNode;
  helper?: ReactNode;
  accent?: 'green' | 'red' | 'blue' | 'neutral';
};

export function MetricCard({ label, value, helper, accent = 'neutral' }: MetricCardProps) {
  const accentColors = {
    green: 'rgba(34, 197, 94, 0.10)',
    red: 'rgba(239, 68, 68, 0.10)',
    blue: 'rgba(56, 189, 248, 0.10)',
    neutral: '#111827',
  };

  const borderColors = {
    green: 'rgba(34, 197, 94, 0.36)',
    red: 'rgba(239, 68, 68, 0.36)',
    blue: 'rgba(56, 189, 248, 0.32)',
    neutral: 'rgba(148, 163, 184, 0.16)',
  };

  return (
    <Card
      sx={{
        ...glassCard,
        ...interactiveGlass,
        background: accentColors[accent],
        border: `1px solid ${borderColors[accent]}`,
        minHeight: 112,
      }}
    >
      <CardContent sx={{ p: 2, '&:last-child': { pb: 2 } }}>
        <Stack spacing={0.75}>
          <Typography
            variant="caption"
            color="text.secondary"
            fontWeight={800}
            sx={{ textTransform: 'uppercase', letterSpacing: 0, fontSize: '0.7rem' }}
          >
            {label}
          </Typography>
          <Typography
            variant="h5"
            fontWeight={900}
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
            <Typography variant="caption" color="text.secondary" sx={{ fontSize: '0.75rem' }}>
              {helper}
            </Typography>
          ) : null}
        </Stack>
      </CardContent>
    </Card>
  );
}
