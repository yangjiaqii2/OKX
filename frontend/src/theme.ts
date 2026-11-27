import { defaultTheme } from 'react-admin';
import type { RaThemeOptions } from 'react-admin';

export const theme: RaThemeOptions = {
  ...defaultTheme,
  palette: {
    mode: 'light',
    primary: { main: '#2457E6', contrastText: '#F5F8FF' },
    secondary: { main: '#5C6570' },
    success: { main: '#0C7A48' },
    warning: { main: '#9A5A12' },
    error: { main: '#C4273A' },
    background: { default: '#F4F6F9', paper: '#FBFCFD' },
    text: { primary: '#171A21', secondary: '#5C6570' },
    divider: 'rgba(23, 26, 33, 0.08)',
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
