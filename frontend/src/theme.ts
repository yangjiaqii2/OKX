import { defaultTheme } from 'react-admin';
import type { RaThemeOptions } from 'react-admin';

export const theme: RaThemeOptions = {
  ...defaultTheme,
  palette: {
    mode: 'light',
    primary: { main: '#16382B', contrastText: '#F8FAF5' },
    secondary: { main: '#5A635D' },
    success: { main: '#157347' },
    warning: { main: '#9A5D14' },
    error: { main: '#BE2B3C' },
    background: { default: '#F7F6F2', paper: '#FFFFFF' },
    text: { primary: '#17211B', secondary: '#5A635D' },
    divider: 'rgba(23, 33, 27, 0.08)',
  },
  typography: {
    ...defaultTheme.typography,
    fontFamily: 'Inter, "Noto Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif',
    button: { textTransform: 'none', fontWeight: 500 },
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: {
          margin: 0,
          backgroundColor: 'var(--bg)',
          color: 'var(--text)',
          fontFamily: 'Inter, "Noto Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif',
        },
      },
    },
  },
};
