import { defaultTheme } from 'react-admin';
import type { RaThemeOptions } from 'react-admin';

export const theme: RaThemeOptions = {
  ...defaultTheme,
  palette: {
    mode: 'dark' as const,
    primary: {
      main: '#00d4aa',
      contrastText: '#0a0f1a',
    },
    secondary: {
      main: '#3b82f6',
    },
    success: {
      main: '#00d4aa',
      dark: '#00b894',
    },
    warning: {
      main: '#f59e0b',
    },
    error: {
      main: '#ef4444',
      dark: '#dc2626',
    },
    background: {
      default: '#0a0f1a',
      paper: '#111827',
    },
    text: {
      primary: '#f1f5f9',
      secondary: '#94a3b8',
    },
  },
  shape: {
    borderRadius: 6,
  },
  typography: {
    ...defaultTheme.typography,
    fontFamily:
      'Inter, "Segoe UI", "Microsoft YaHei", system-ui, -apple-system, sans-serif',
    h5: {
      fontWeight: 700,
      letterSpacing: 0.5,
      fontSize: '1.25rem',
    },
    h6: {
      fontWeight: 700,
      letterSpacing: 0.5,
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
    MuiButton: {
      styleOverrides: {
        root: {
          borderRadius: 6,
          textTransform: 'none',
          fontWeight: 600,
          fontSize: '0.8125rem',
          letterSpacing: 0.3,
        },
        contained: {
          boxShadow: '0 2px 8px rgba(0,0,0,0.3)',
        },
      },
    },
    MuiCard: {
      styleOverrides: {
        root: {
          backgroundImage: 'none',
          background: '#10141f',
          border: '1px solid rgba(148, 163, 184, 0.12)',
          boxShadow: '0 12px 34px rgba(0, 0, 0, 0.28)',
        },
      },
    },
    MuiPaper: {
      styleOverrides: {
        root: {
          backgroundImage: 'none',
          borderColor: 'rgba(148, 163, 184, 0.12)',
        },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: {
          borderRadius: 4,
          fontWeight: 600,
          fontSize: '0.75rem',
        },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: {
          borderBottomColor: 'rgba(148, 163, 184, 0.08)',
          fontSize: '0.8125rem',
          fontFamily: '"JetBrains Mono", "SF Mono", monospace',
        },
        head: {
          color: '#cbd5e1',
          fontWeight: 700,
          fontSize: '0.75rem',
          textTransform: 'uppercase',
          letterSpacing: 0.8,
        },
      },
    },
    MuiAppBar: {
      styleOverrides: {
        root: {
          background: 'rgba(10, 15, 26, 0.92)',
          backdropFilter: 'blur(14px)',
          borderBottom: '1px solid rgba(148, 163, 184, 0.12)',
        },
      },
    },
    MuiDrawer: {
      styleOverrides: {
        paper: {
          background: '#0f172a',
          borderRight: '1px solid rgba(148, 163, 184, 0.12)',
        },
      },
    },
  },
};
