import { defaultTheme } from 'react-admin';
import type { RaThemeOptions } from 'react-admin';

export const theme: RaThemeOptions = {
  ...defaultTheme,
  palette: {
    mode: 'dark' as const,
    primary: {
      main: '#14b8a6',
      dark: '#0f766e',
      contrastText: '#04111f',
    },
    secondary: {
      main: '#38bdf8',
    },
    info: {
      main: '#38bdf8',
    },
    success: {
      main: '#22c55e',
      dark: '#15803d',
    },
    warning: {
      main: '#f59e0b',
    },
    error: {
      main: '#ef4444',
      dark: '#dc2626',
    },
    background: {
      default: '#080c14',
      paper: '#111827',
    },
    text: {
      primary: '#e5edf6',
      secondary: '#94a3b8',
    },
    divider: 'rgba(148, 163, 184, 0.14)',
  },
  shape: {
    borderRadius: 8,
  },
  typography: {
    ...defaultTheme.typography,
    fontFamily:
      'Inter, "Segoe UI", "Microsoft YaHei", system-ui, -apple-system, sans-serif',
    h5: {
      fontWeight: 900,
      letterSpacing: 0,
      fontSize: '1.35rem',
    },
    h6: {
      fontWeight: 850,
      letterSpacing: 0,
      fontSize: '1.1rem',
    },
    body1: {
      fontSize: '0.875rem',
    },
    body2: {
      fontSize: '0.8125rem',
    },
  },
  components: {
    ...defaultTheme.components,
    MuiCssBaseline: {
      styleOverrides: {
        body: {
          background:
            'linear-gradient(180deg, #080c14 0%, #0b111c 46%, #080c14 100%)',
          backgroundAttachment: 'fixed',
          color: '#e5edf6',
        },
        '*': {
          boxSizing: 'border-box',
          scrollbarColor: 'rgba(148, 163, 184, 0.36) transparent',
        },
        '*::-webkit-scrollbar': {
          width: 10,
          height: 10,
        },
        '*::-webkit-scrollbar-thumb': {
          borderRadius: 8,
          background: 'rgba(148, 163, 184, 0.30)',
          border: '2px solid transparent',
          backgroundClip: 'content-box',
        },
      },
    },
    MuiButton: {
      styleOverrides: {
        root: {
          borderRadius: 8,
          textTransform: 'none',
          fontWeight: 800,
          fontSize: '0.8125rem',
          letterSpacing: 0,
          minHeight: 36,
          boxShadow: 'none',
          transition: 'background 140ms ease, border-color 140ms ease, color 140ms ease',
          '&:active': {
            transform: 'translateY(1px)',
          },
        },
        contained: {
          boxShadow: 'none',
          '&:hover': {
            boxShadow: 'none',
          },
        },
        containedPrimary: {
          background: '#14b8a6',
          color: '#031313',
          '&:hover': {
            background: '#2dd4bf',
          },
        },
        outlined: {
          borderColor: 'rgba(148, 163, 184, 0.24)',
          background: '#0f172a',
          '&:hover': {
            borderColor: 'rgba(20, 184, 166, 0.48)',
            background: '#111c2e',
          },
        },
      },
    },
    MuiCard: {
      styleOverrides: {
        root: {
          backgroundImage: 'none',
          position: 'relative',
          overflow: 'hidden',
          background: '#111827',
          border: '1px solid rgba(148, 163, 184, 0.16)',
          boxShadow: '0 16px 42px rgba(0, 0, 0, 0.22)',
        },
      },
    },
    MuiPaper: {
      styleOverrides: {
        root: {
          backgroundImage: 'none',
          borderColor: 'rgba(148, 163, 184, 0.16)',
          backgroundColor: '#111827',
        },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: {
          borderRadius: 6,
          fontWeight: 800,
          fontSize: '0.75rem',
          borderColor: 'rgba(148, 163, 184, 0.24)',
          background: '#0f172a',
        },
        icon: {
          fontSize: 15,
        },
      },
    },
    MuiTextField: {
      styleOverrides: {
        root: {
          '& .MuiOutlinedInput-root': {
            borderRadius: 8,
            background: '#0b1220',
          },
        },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: {
          borderBottomColor: 'rgba(148, 163, 184, 0.12)',
          fontSize: '0.8125rem',
          fontFamily: '"JetBrains Mono", "SF Mono", monospace',
        },
        head: {
          color: '#cbd5e1',
          fontWeight: 900,
          fontSize: '0.75rem',
          textTransform: 'uppercase',
          letterSpacing: 0,
          background: '#0b1220',
        },
      },
    },
    MuiTableRow: {
      styleOverrides: {
        root: {
          '&:hover': {
            backgroundColor: 'rgba(20, 184, 166, 0.06)',
          },
        },
      },
    },
    MuiAppBar: {
      styleOverrides: {
        root: {
          background: '#0b111c',
          borderBottom: '1px solid rgba(148, 163, 184, 0.14)',
          boxShadow: '0 10px 30px rgba(0, 0, 0, 0.22)',
        },
      },
    },
    MuiDrawer: {
      styleOverrides: {
        paper: {
          background: '#0b111c',
          borderRight: '1px solid rgba(148, 163, 184, 0.14)',
        },
      },
    },
    MuiDialog: {
      styleOverrides: {
        paper: {
          borderRadius: 8,
          border: '1px solid rgba(148, 163, 184, 0.18)',
          background: '#111827',
        },
      },
    },
    MuiAlert: {
      styleOverrides: {
        root: {
          borderRadius: 8,
          border: '1px solid rgba(255,255,255,0.08)',
        },
      },
    },
  },
};
