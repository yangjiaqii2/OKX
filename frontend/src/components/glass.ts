import type { SxProps, Theme } from '@mui/material/styles';

export const glassPanel = {
  position: 'relative',
  overflow: 'hidden',
  border: '1px solid #242824',
  background: '#0B0C0A',
  boxShadow: 'none',
} satisfies SxProps<Theme>;

export const glassCard = {
  ...glassPanel,
  borderRadius: 1,
} satisfies SxProps<Theme>;

export const compactGlass = {
  border: '1px solid #242824',
  background: '#111311',
  boxShadow: 'none',
} satisfies SxProps<Theme>;

export const interactiveGlass = {
  transition: 'border-color 140ms ease, background 140ms ease',
  '&:hover': {
    borderColor: '#8B938B',
    boxShadow: 'none',
  },
  '&:active': {
    boxShadow: 'none',
  },
} satisfies SxProps<Theme>;
