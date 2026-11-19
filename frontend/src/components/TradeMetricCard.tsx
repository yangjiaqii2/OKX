import { Card, CardContent, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';

type TradeMetricCardProps = {
  label: string;
  value: ReactNode;
  helper?: ReactNode;
  accent?: 'success' | 'error' | 'warning' | 'info' | 'neutral';
};

export function TradeMetricCard({ label, value, helper, accent = 'neutral' }: TradeMetricCardProps) {
  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Stack spacing={0.5} minWidth={0}>
          <Typography variant="caption" color="text.secondary" fontWeight={650}>
            {label}
          </Typography>
          <Typography
            component="div"
            fontWeight={750}
            color={accent === 'neutral' ? 'text.primary' : `${accent}.main`}
            sx={{ fontSize: { xs: '1.125rem', sm: '1.375rem' }, lineHeight: 1.3, overflowWrap: 'anywhere' }}
          >
            {value}
          </Typography>
          {helper ? <Typography variant="caption" color="text.secondary" sx={{ overflowWrap: 'anywhere' }}>{helper}</Typography> : null}
        </Stack>
      </CardContent>
    </Card>
  );
}
