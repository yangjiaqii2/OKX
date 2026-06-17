import { Chip } from '@mui/material';
import { formatStatus, formatTrend } from '../formatters';

type StatusChipProps = {
  value?: string | boolean | number | null;
};

export function StatusChip({ value }: StatusChipProps) {
  const text = String(value ?? 'UNKNOWN');

  const colorMap: Record<string, 'success' | 'error' | 'warning' | 'info' | 'default'> = {
    'true': 'success',
    'LOW': 'success',
    'PENDING_CONFIRM': 'warning',
    'BULLISH': 'success',
    'OKX_REAL': 'success',
    'false': 'error',
    'BLOCKED': 'error',
    'REJECTED': 'error',
    'FAILED': 'error',
    'BEARISH': 'error',
    'OKX_ERROR': 'error',
    'CANCELLED': 'warning',
    'EXPIRED': 'warning',
    'OKX_UNBOUND': 'warning',
    'OKX_EMPTY': 'warning',
    'MEDIUM': 'warning',
    'HIGH': 'error',
    'CONFIRMED': 'info',
    'SUBMITTED': 'info',
    'EXECUTED': 'success',
    'SKIPPED': 'default',
  };

  const color = colorMap[text] ?? 'default';

  const label = text === 'BULLISH' || text === 'BEARISH' || text === 'NEUTRAL' ? formatTrend(text) : formatStatus(text);
  
  return (
    <Chip 
      size="small" 
      label={label} 
      color={color} 
      variant="filled"
      sx={{ 
        fontWeight: 700, 
        fontSize: '0.75rem',
        letterSpacing: 0,
        height: 24,
        maxWidth: '100%',
      }} 
    />
  );
}
