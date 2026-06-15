import type { SxProps, Theme } from '@mui/material/styles';

export const glassPanel = {
  position: 'relative',
  overflow: 'hidden',
  border: '1px solid rgba(180, 205, 255, 0.16)',
  background:
    'linear-gradient(145deg, rgba(16, 24, 39, 0.78), rgba(8, 13, 24, 0.58))',
  backdropFilter: 'blur(22px) saturate(150%)',
  WebkitBackdropFilter: 'blur(22px) saturate(150%)',
  boxShadow:
    'inset 0 1px 0 rgba(255, 255, 255, 0.10), 0 22px 70px rgba(0, 0, 0, 0.32)',
  '&::before': {
    content: '""',
    position: 'absolute',
    inset: 0,
    pointerEvents: 'none',
    background:
      'radial-gradient(circle at 18% 0%, rgba(255,255,255,0.16), transparent 28%), linear-gradient(90deg, rgba(255,255,255,0.08), transparent 42%, rgba(255,255,255,0.05))',
  },
} satisfies SxProps<Theme>;

export const glassCard = {
  ...glassPanel,
  borderRadius: 3,
} satisfies SxProps<Theme>;

export const compactGlass = {
  border: '1px solid rgba(180, 205, 255, 0.13)',
  background: 'rgba(9, 16, 29, 0.54)',
  backdropFilter: 'blur(16px) saturate(145%)',
  WebkitBackdropFilter: 'blur(16px) saturate(145%)',
  boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.07)',
} satisfies SxProps<Theme>;

export const interactiveGlass = {
  transition: 'transform 160ms ease, border-color 160ms ease, background 160ms ease, box-shadow 160ms ease',
  '&:hover': {
    transform: 'translateY(-2px)',
    borderColor: 'rgba(0, 212, 170, 0.38)',
    boxShadow:
      'inset 0 1px 0 rgba(255,255,255,0.12), 0 26px 80px rgba(0, 0, 0, 0.38)',
  },
  '&:active': {
    transform: 'translateY(0)',
  },
} satisfies SxProps<Theme>;
