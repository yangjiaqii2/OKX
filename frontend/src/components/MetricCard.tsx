import { Card, CardContent, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';

type MetricCardProps = {
  label: string;
  value: ReactNode;
  helper?: ReactNode;
  accent?: 'green' | 'red' | 'blue' | 'neutral';
};

export function MetricCard({ label, value, helper, accent = 'neutral' }: MetricCardProps) {
  const accentColors = {
    green: 'linear-gradient(135deg, rgba(0, 212, 170, 0.15), rgba(0, 212, 170, 0.02))',
    red: 'linear-gradient(135deg, rgba(239, 68, 68, 0.15), rgba(239, 68, 68, 0.02))',
    blue: 'linear-gradient(135deg, rgba(59, 130, 246, 0.15), rgba(59, 130, 246, 0.02))',
    neutral: 'linear-gradient(145deg, #111827 0%, #0f172a 100%)',
  };

  const borderColors = {
    green: 'rgba(0, 212, 170, 0.3)',
    red: 'rgba(239, 68, 68, 0.3)',
    blue: 'rgba(59, 130, 246, 0.3)',
    neutral: 'rgba(148, 163, 184, 0.12)',
  };

  return (
    <Card
      sx={{
        background: accentColors[accent],
        border: `1px solid ${borderColors[accent]}`,
        boxShadow: '0 4px 20px rgba(0, 0, 0, 0.4)',
        transition: 'all 0.2s ease',
        '&:hover': {
          boxShadow: '0 8px 30px rgba(0, 0, 0, 0.5)',
          transform: 'translateY(-1px)',
        },
      }}
    >
      <CardContent sx={{ p: 2, '&:last-child': { pb: 2 } }}>
        <Stack spacing={0.75}>
          <Typography
            variant="caption"
            color="text.secondary"
            fontWeight={700}
            sx={{ textTransform: 'uppercase', letterSpacing: 1, fontSize: '0.7rem' }}
          >
            {label}
          </Typography>
          <Typography
            variant="h5"
            fontWeight={700}
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
