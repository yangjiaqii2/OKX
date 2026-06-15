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
      main: '#8bd3ff',
    },
    info: {
      main: '#8bd3ff',
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
      default: '#050914',
      paper: 'rgba(12, 18, 32, 0.82)',
    },
    text: {
      primary: '#f7fbff',
      secondary: '#9fb1c8',
    },
    divider: 'rgba(180, 205, 255, 0.12)',
  },
  shape: {
    borderRadius: 14,
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
            'radial-gradient(circle at 10% -10%, rgba(0, 212, 170, 0.18), transparent 30%), radial-gradient(circle at 92% 8%, rgba(139, 211, 255, 0.16), transparent 26%), linear-gradient(135deg, #050914 0%, #07111f 48%, #050914 100%)',
          backgroundAttachment: 'fixed',
        },
        '*': {
          scrollbarColor: 'rgba(159, 177, 200, 0.34) transparent',
        },
        '*::-webkit-scrollbar': {
          width: 10,
          height: 10,
        },
        '*::-webkit-scrollbar-thumb': {
          borderRadius: 999,
          background: 'rgba(159, 177, 200, 0.30)',
          border: '2px solid transparent',
          backgroundClip: 'content-box',
        },
      },
    },
    MuiButton: {
      styleOverrides: {
        root: {
          borderRadius: 12,
          textTransform: 'none',
          fontWeight: 800,
          fontSize: '0.8125rem',
          letterSpacing: 0,
          minHeight: 36,
          transition: 'transform 120ms ease, box-shadow 160ms ease, border-color 160ms ease, background 160ms ease',
          '&:active': {
            transform: 'translateY(1px) scale(0.99)',
          },
        },
        contained: {
          boxShadow: '0 14px 36px rgba(0, 212, 170, 0.18)',
          background: 'linear-gradient(135deg, #4dffe0 0%, #00d4aa 48%, #00a986 100%)',
          color: '#03100d',
          '&:hover': {
            boxShadow: '0 18px 44px rgba(0, 212, 170, 0.26)',
          },
        },
        outlined: {
          borderColor: 'rgba(180, 205, 255, 0.20)',
          background: 'rgba(12, 18, 32, 0.36)',
          backdropFilter: 'blur(12px)',
        },
      },
    },
    MuiCard: {
      styleOverrides: {
        root: {
          backgroundImage: 'none',
          position: 'relative',
          overflow: 'hidden',
          background:
            'linear-gradient(145deg, rgba(16, 24, 39, 0.78), rgba(8, 13, 24, 0.58))',
          border: '1px solid rgba(180, 205, 255, 0.16)',
          backdropFilter: 'blur(22px) saturate(150%)',
          boxShadow:
            'inset 0 1px 0 rgba(255, 255, 255, 0.10), 0 22px 70px rgba(0, 0, 0, 0.32)',
        },
      },
    },
    MuiPaper: {
      styleOverrides: {
        root: {
          backgroundImage: 'none',
          borderColor: 'rgba(180, 205, 255, 0.14)',
          backgroundColor: 'rgba(12, 18, 32, 0.84)',
          backdropFilter: 'blur(22px) saturate(145%)',
        },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: {
          borderRadius: 999,
          fontWeight: 800,
          fontSize: '0.75rem',
          borderColor: 'rgba(180, 205, 255, 0.20)',
          background: 'rgba(180, 205, 255, 0.06)',
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
            borderRadius: 12,
            background: 'rgba(7, 13, 24, 0.50)',
            backdropFilter: 'blur(12px)',
          },
        },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: {
          borderBottomColor: 'rgba(180, 205, 255, 0.09)',
          fontSize: '0.8125rem',
          fontFamily: '"JetBrains Mono", "SF Mono", monospace',
        },
        head: {
          color: '#cbd8ea',
          fontWeight: 900,
          fontSize: '0.75rem',
          textTransform: 'uppercase',
          letterSpacing: 0,
          background: 'rgba(6, 12, 22, 0.42)',
        },
      },
    },
    MuiAppBar: {
      styleOverrides: {
        root: {
          background: 'rgba(5, 9, 20, 0.70)',
          backdropFilter: 'blur(22px) saturate(150%)',
          borderBottom: '1px solid rgba(180, 205, 255, 0.14)',
          boxShadow: '0 18px 60px rgba(0, 0, 0, 0.24)',
        },
      },
    },
    MuiDrawer: {
      styleOverrides: {
        paper: {
          background:
            'linear-gradient(180deg, rgba(8, 13, 24, 0.88), rgba(5, 9, 20, 0.78))',
          backdropFilter: 'blur(24px) saturate(150%)',
          borderRight: '1px solid rgba(180, 205, 255, 0.14)',
        },
      },
    },
    MuiDialog: {
      styleOverrides: {
        paper: {
          borderRadius: 18,
          border: '1px solid rgba(180, 205, 255, 0.16)',
          background:
            'linear-gradient(145deg, rgba(16, 24, 39, 0.92), rgba(6, 12, 22, 0.88))',
        },
      },
    },
    MuiAlert: {
      styleOverrides: {
        root: {
          borderRadius: 14,
          border: '1px solid rgba(255,255,255,0.10)',
          backdropFilter: 'blur(14px)',
        },
      },
    },
  },
};
