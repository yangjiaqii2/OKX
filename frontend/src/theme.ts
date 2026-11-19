import type { Theme } from '@mui/material/styles';
import { defaultTheme } from 'react-admin';
import type { RaThemeOptions } from 'react-admin';
import { colors, elevation, fontFamily, motion, numeric, radius, mobileNavHeight, safeArea } from './designTokens';

const focus = { outline: `2px solid ${colors.primary}`, outlineOffset: 2 };
const border = `1px solid ${colors.divider}`;
const chipTones = {
  '&.MuiChip-colorPrimary, &.MuiChip-colorInfo': { color: colors.primaryDark, backgroundColor: colors.primarySoft, borderColor: colors.primaryBorder },
  '&.MuiChip-colorSuccess': { color: colors.success, backgroundColor: colors.successSoft, borderColor: colors.successBorder },
  '&.MuiChip-colorError': { color: colors.error, backgroundColor: colors.errorSoft, borderColor: colors.errorBorder },
  '&.MuiChip-colorWarning': { color: colors.warning, backgroundColor: colors.warningSoft, borderColor: colors.warningBorder },
};

export const theme: RaThemeOptions = {
  ...defaultTheme,
  palette: {
    mode: 'light',
    primary: { main: colors.primary, dark: colors.primaryDark, contrastText: colors.paper },
    secondary: { main: colors.muted },
    info: { main: colors.primary, contrastText: colors.paper },
    success: { main: colors.success, dark: colors.successDark, contrastText: colors.paper },
    warning: { main: colors.warning, contrastText: colors.paper },
    error: { main: colors.error, dark: colors.errorDark, contrastText: colors.paper },
    background: { default: colors.background, paper: colors.paper },
    text: { primary: colors.text, secondary: colors.muted, disabled: colors.disabled },
    divider: colors.divider,
    action: {
      hover: colors.subtle,
      selected: colors.primarySoft,
      disabled: colors.disabled,
      disabledBackground: colors.divider,
    },
  },
  spacing: 8,
  shape: { borderRadius: radius },
  sidebar: { width: 224, closedWidth: 56 },
  typography: {
    ...defaultTheme.typography,
    fontFamily,
    h4: { fontSize: '1.75rem', fontWeight: 750, lineHeight: 1.25 },
    h5: { fontSize: '1.375rem', fontWeight: 750, lineHeight: 1.3 },
    h6: { fontSize: '1.125rem', fontWeight: 700, lineHeight: 1.4 },
    body1: { fontSize: '0.9375rem', lineHeight: 1.6 },
    body2: { fontSize: '0.875rem', lineHeight: 1.55 },
    caption: { fontSize: '0.75rem', lineHeight: 1.5 },
    button: { textTransform: 'none', fontWeight: 700, letterSpacing: 0 },
  },
  components: {
    ...defaultTheme.components,
    MuiCssBaseline: {
      styleOverrides: {
        html: { colorScheme: 'light' },
        body: { ...numeric, margin: 0 },
        '#root': { minWidth: 0 },
        '::selection': { background: colors.primaryBorder, color: colors.text },
        '@media (prefers-reduced-motion: reduce)': {
          '*, *::before, *::after': { animationDuration: '0.01ms', transitionDuration: '0.01ms', scrollBehavior: 'auto' },
        },
      },
    },
    MuiButtonBase: {
      styleOverrides: {
        root: { '&.Mui-focusVisible, &:focus-visible': focus },
      },
    },
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: {
          minHeight: 44,
          maxWidth: '100%',
          paddingInline: 16,
          lineHeight: 1.3,
          transition: `background-color ${motion}, border-color ${motion}, color ${motion}, transform ${motion}`,
          '&:active': { transform: 'translateY(1px)' },
          '&.Mui-disabled': { color: colors.disabled },
        },
        outlined: {
          backgroundColor: colors.paper,
          borderColor: colors.input,
          '&:hover': { borderColor: 'currentColor', backgroundColor: colors.primarySoft },
          '&.MuiButton-colorError:hover': { backgroundColor: colors.errorSoft },
        },
      },
    },
    MuiIconButton: {
      styleOverrides: {
        root: { minWidth: 44, minHeight: 44, color: colors.muted, '&:hover': { backgroundColor: colors.primarySoft } },
      },
    },
    MuiToggleButton: {
      styleOverrides: {
        root: {
          minHeight: 44,
          color: colors.muted,
          '&.Mui-selected': { color: colors.primaryDark, backgroundColor: colors.primarySoft },
          '&.Mui-selected:hover': { backgroundColor: colors.primaryBorder },
        },
      },
    },
    MuiTextField: {
      defaultProps: { variant: 'outlined', size: 'small', fullWidth: true },
    },
    MuiFormControl: {
      defaultProps: { variant: 'outlined', size: 'small' },
    },
    MuiOutlinedInput: {
      styleOverrides: {
        root: {
          minHeight: 48,
          maxWidth: '100%',
          backgroundColor: colors.paper,
          '&:hover .MuiOutlinedInput-notchedOutline': { borderColor: colors.primary },
          '&.Mui-focused': { boxShadow: `0 0 0 3px ${colors.primarySoft}` },
          '&.Mui-error.Mui-focused': { boxShadow: `0 0 0 3px ${colors.errorSoft}` },
          '&.Mui-disabled': { backgroundColor: colors.background },
        },
        notchedOutline: { borderColor: colors.input },
      },
    },
    MuiInputBase: {
      styleOverrides: {
        root: ({ theme }: { theme: Theme }) => ({ [theme.breakpoints.down('md')]: { fontSize: '1rem' } }),
        input: { ...numeric, minWidth: 0 },
      },
    },
    MuiInputLabel: { styleOverrides: { root: { color: colors.muted } } },
    MuiFormHelperText: { styleOverrides: { root: { marginInline: 0, overflowWrap: 'anywhere' } } },
    MuiSwitch: { styleOverrides: { root: { minHeight: 44 }, track: { backgroundColor: colors.input } } },
    MuiCard: {
      styleOverrides: {
        root: { minWidth: 0, border, boxShadow: elevation.card },
      },
    },
    MuiCardContent: {
      styleOverrides: { root: { padding: 16, '&:last-child': { paddingBottom: 16 } } },
    },
    MuiPaper: { styleOverrides: { root: { backgroundImage: 'none' } } },
    MuiChip: {
      styleOverrides: {
        root: {
          ...chipTones,
          maxWidth: '100%',
          height: 'auto',
          minHeight: 24,
          color: colors.muted,
          backgroundColor: colors.background,
          borderColor: colors.divider,
          borderRadius: radius,
          fontSize: '0.75rem',
          fontWeight: 700,
        },
        label: { paddingBlock: 3, lineHeight: 1.4, whiteSpace: 'normal', overflowWrap: 'anywhere' },
        icon: { color: 'inherit' },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: { ...numeric, borderBottomColor: colors.divider, padding: '12px 16px' },
        head: { color: colors.muted, backgroundColor: colors.subtle, fontWeight: 700, whiteSpace: 'nowrap' },
        body: { fontSize: '0.8125rem' },
      },
    },
    MuiTableRow: {
      styleOverrides: {
        root: {
          '&:last-child td': { borderBottom: 0 },
          '&.MuiTableRow-hover:hover': { backgroundColor: colors.primarySoft },
        },
      },
    },
    MuiTabs: { styleOverrides: { root: { minHeight: 48 } } },
    MuiTab: {
      styleOverrides: {
        root: { minHeight: 48, minWidth: 44, textTransform: 'none', fontWeight: 700, letterSpacing: 0 },
      },
    },
    MuiMenuItem: { styleOverrides: { root: { minHeight: 44 } } },
    MuiAppBar: {
      styleOverrides: {
        root: { color: colors.text, backgroundColor: colors.paper, borderBottom: border, boxShadow: 'none' },
      },
    },
    MuiBackdrop: { styleOverrides: { root: { '&:not(.MuiBackdrop-invisible)': { backgroundColor: colors.overlay } } } },
    MuiDialog: {
      styleOverrides: {
        paper: ({ theme }: { theme: Theme }) => ({
          border,
          boxShadow: elevation.overlay,
          overflowWrap: 'anywhere',
          [theme.breakpoints.down('sm')]: {
            margin: 12,
            width: `calc(100% - 24px - ${safeArea.left} - ${safeArea.right})`,
            maxHeight: `calc(100dvh - 24px - ${safeArea.top} - ${safeArea.bottom})`,
          },
        }),
        container: {
          padding: `${safeArea.top} ${safeArea.right} ${safeArea.bottom} ${safeArea.left}`,
        },
      },
    },
    MuiDialogTitle: { styleOverrides: { root: { padding: 16, fontWeight: 750 } } },
    MuiDialogContent: {
      styleOverrides: {
        root: {
          padding: 16,
          '.MuiDialogTitle-root + &': { paddingTop: 12 },
        },
      },
    },
    MuiDialogActions: {
      styleOverrides: { root: { flexWrap: 'wrap', gap: 8, padding: 16, '& > :not(style)': { margin: 0 } } },
    },
    MuiAlert: {
      styleOverrides: {
        root: { border, alignItems: 'flex-start' },
        standardInfo: { color: colors.primaryDark, backgroundColor: colors.primarySoft, borderColor: colors.primaryBorder },
        standardSuccess: { color: colors.success, backgroundColor: colors.successSoft, borderColor: colors.successBorder },
        standardError: { color: colors.error, backgroundColor: colors.errorSoft, borderColor: colors.errorBorder },
        standardWarning: { color: colors.warning, backgroundColor: colors.warningSoft, borderColor: colors.warningBorder },
        message: { minWidth: 0, overflowWrap: 'anywhere' },
      },
    },
    MuiSnackbar: {
      styleOverrides: {
        anchorOriginBottomCenter: ({ theme }: { theme: Theme }) => ({
          [theme.breakpoints.down('md')]: { bottom: `calc(${mobileNavHeight + 12}px + ${safeArea.bottom})` },
        }),
      },
    },
    MuiTooltip: {
      styleOverrides: { tooltip: { backgroundColor: colors.text, color: colors.paper, fontSize: '0.75rem', overflowWrap: 'anywhere' } },
    },
  },
};
