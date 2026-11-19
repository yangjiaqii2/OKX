export const colors = {
  background: '#f5f7fa',
  paper: '#ffffff',
  subtle: '#f8fafd',
  text: '#18243a',
  muted: '#4b5c74',
  disabled: '#64748b',
  divider: '#dce4ee',
  input: '#8494aa',
  primary: '#2459d3',
  primaryDark: '#1945ad',
  primarySoft: '#edf3ff',
  primaryBorder: '#c5d7ff',
  success: '#087451',
  successDark: '#065b40',
  successSoft: '#e9f7f0',
  successBorder: '#b2ddc8',
  error: '#b42336',
  errorDark: '#951c2c',
  errorSoft: '#fff0f2',
  errorBorder: '#f0c3cb',
  warning: '#875009',
  warningSoft: '#fff5e3',
  warningBorder: '#eed6a8',
  overlay: 'rgba(24, 36, 58, .44)',
} as const;

export const radius = 10;
export const elevation = {
  card: '0 1px 2px rgba(24, 36, 58, .05)',
  overlay: '0 12px 36px rgba(24, 36, 58, .14)',
};
export const motion = '180ms ease';
export const fontFamily = 'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif';
export const numeric = { fontVariantNumeric: 'tabular-nums' };
export const safeArea = {
  top: 'env(safe-area-inset-top, 0px)',
  right: 'env(safe-area-inset-right, 0px)',
  bottom: 'env(safe-area-inset-bottom, 0px)',
  left: 'env(safe-area-inset-left, 0px)',
};
export const appBarHeight = 64;
export const mobileNavHeight = 64;
