import type { SxProps, Theme } from '@mui/material/styles';

export const glassPanel = {
  position: 'relative',
  overflow: 'hidden',
  border: '1px solid rgba(148, 163, 184, 0.16)',
  background: '#111827',
  boxShadow: '0 16px 42px rgba(0, 0, 0, 0.22)',
} satisfies SxProps<Theme>;

export const glassCard = {
  ...glassPanel,
  borderRadius: 1,
} satisfies SxProps<Theme>;

export const compactGlass = {
  border: '1px solid rgba(148, 163, 184, 0.14)',
  background: '#0f172a',
  boxShadow: 'none',
} satisfies SxProps<Theme>;

export const interactiveGlass = {
  transition: 'border-color 140ms ease, background 140ms ease, box-shadow 140ms ease',
  '&:hover': {
    borderColor: 'rgba(20, 184, 166, 0.44)',
    boxShadow: '0 18px 48px rgba(0, 0, 0, 0.30)',
  },
  '&:active': {
    boxShadow: '0 10px 26px rgba(0, 0, 0, 0.24)',
  },
} satisfies SxProps<Theme>;
