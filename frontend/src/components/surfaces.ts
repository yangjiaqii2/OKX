import type { SxProps, Theme } from '@mui/material/styles';
import { colors, elevation, motion } from '../designTokens';

export const panel = {
  minWidth: 0,
  border: 1,
  borderColor: 'divider',
  borderRadius: 1,
  bgcolor: 'background.paper',
  boxShadow: elevation.card,
} satisfies SxProps<Theme>;

export const insetPanel = {
  minWidth: 0,
  border: 1,
  borderColor: 'divider',
  borderRadius: 1,
  bgcolor: colors.subtle,
} satisfies SxProps<Theme>;

export const interactivePanel = {
  transition: `border-color ${motion}, box-shadow ${motion}`,
  '&:focus-within, &:hover': {
    borderColor: colors.primaryBorder,
    boxShadow: elevation.card,
  },
} satisfies SxProps<Theme>;
