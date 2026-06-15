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
    green: 'linear-gradient(135deg, rgba(0, 212, 170, 0.16), rgba(8, 13, 24, 0.58))',
    red: 'linear-gradient(135deg, rgba(239, 68, 68, 0.16), rgba(8, 13, 24, 0.58))',
    blue: 'linear-gradient(135deg, rgba(139, 211, 255, 0.14), rgba(8, 13, 24, 0.58))',
    neutral: 'linear-gradient(145deg, rgba(16, 24, 39, 0.72), rgba(8, 13, 24, 0.58))',
  };

  const borderColors = {
    green: 'rgba(0, 212, 170, 0.36)',
    red: 'rgba(239, 68, 68, 0.36)',
    blue: 'rgba(139, 211, 255, 0.32)',
    neutral: 'rgba(180, 205, 255, 0.15)',
  };

  return (
    <Card
      sx={{
        ...glassCard,
        ...interactiveGlass,
        background: accentColors[accent],
        border: `1px solid ${borderColors[accent]}`,
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
            sx={{ fontFamily: '"JetBrains Mono", "SF Mono", monospace', fontSize: '1.5rem' }}
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
