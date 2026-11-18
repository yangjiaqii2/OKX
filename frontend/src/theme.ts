import { defaultTheme } from 'react-admin';
import type { RaThemeOptions } from 'react-admin';

export const theme: RaThemeOptions = {
  ...defaultTheme,
  palette: {
    mode: 'dark' as const,
    primary: {
      main: '#E8ECE7',
      dark: '#B8C0B8',
      contrastText: '#000000',
    },
    secondary: {
      main: '#7AA2F7',
    },
    info: {
      main: '#38bdf8',
    },
    success: {
      main: '#00E676',
      dark: '#00B85C',
    },
    warning: {
      main: '#FFB800',
    },
    error: {
      main: '#FF3B30',
      dark: '#D92D24',
    },
    background: {
      default: '#000000',
      paper: '#0B0C0A',
    },
    text: {
      primary: '#E8ECE7',
      secondary: '#8B938B',
    },
    divider: '#242824',
  },
  shape: {
    borderRadius: 4,
  },
  typography: {
    ...defaultTheme.typography,
    fontFamily:
      '"IBM Plex Mono", "JetBrains Mono", "SFMono-Regular", "Microsoft YaHei", ui-monospace, monospace',
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
          background: '#000000',
          color: '#E8ECE7',
          overflowX: 'hidden',
        },
        '*': {
          boxSizing: 'border-box',
          scrollbarColor: 'rgba(148, 163, 184, 0.36) transparent',
          fontVariantNumeric: 'tabular-nums',
        },
        '*::-webkit-scrollbar': {
          width: 10,
          height: 10,
        },
        '*::-webkit-scrollbar-thumb': {
          borderRadius: 4,
          background: 'rgba(148, 163, 184, 0.30)',
          border: '2px solid transparent',
          backgroundClip: 'content-box',
        },
      },
    },
    MuiButton: {
      styleOverrides: {
        root: {
          borderRadius: 3,
          textTransform: 'none',
          fontWeight: 800,
          fontSize: '0.8125rem',
          letterSpacing: 0,
          minHeight: 44,
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
          background: '#E8ECE7',
          color: '#000000',
          '&:hover': {
            background: '#FFFFFF',
          },
        },
        outlined: {
          borderColor: 'rgba(148, 163, 184, 0.24)',
          background: '#0B0C0A',
          '&:hover': {
            borderColor: '#8B938B',
            background: '#111311',
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
          background: '#0B0C0A',
          border: '1px solid #242824',
          boxShadow: 'none',
        },
      },
    },
    MuiPaper: {
      styleOverrides: {
        root: {
          backgroundImage: 'none',
          borderColor: 'rgba(148, 163, 184, 0.16)',
          backgroundColor: '#0B0C0A',
        },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: {
          borderRadius: 3,
          fontWeight: 800,
          fontSize: '0.75rem',
          borderColor: 'rgba(148, 163, 184, 0.24)',
          background: '#111311',
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
            borderRadius: 4,
            background: '#080A08',
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
          background: '#080A08',
        },
      },
    },
    MuiTableRow: {
      styleOverrides: {
        root: {
          '&:hover': {
            backgroundColor: '#111311',
          },
        },
      },
    },
    MuiAppBar: {
      styleOverrides: {
        root: {
          background: '#080A08',
          borderBottom: '1px solid #242824',
          boxShadow: 'none',
        },
      },
    },
    MuiDrawer: {
      styleOverrides: {
        paper: {
          background: '#080A08',
          borderRight: '1px solid #242824',
        },
      },
    },
    MuiDialog: {
      styleOverrides: {
        paper: {
          borderRadius: 4,
          border: '1px solid #242824',
          background: '#0B0C0A',
        },
      },
    },
    MuiAlert: {
      styleOverrides: {
        root: {
          borderRadius: 4,
          border: '1px solid rgba(255,255,255,0.08)',
        },
      },
    },
  },
};
