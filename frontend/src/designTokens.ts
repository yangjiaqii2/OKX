export const colors = {
  background: 'var(--bg)',
  paper: 'var(--field)',
  subtle: 'var(--sunken)',
  text: 'var(--text)',
  muted: 'var(--muted)',
  disabled: 'var(--faint)',
  divider: 'var(--line-strong)',
  input: 'var(--line-strong)',
  primary: 'var(--accent)',
  primaryDark: 'var(--accent-ink)',
  primarySoft: 'var(--accent-soft)',
  primaryBorder: 'var(--accent)',
  success: 'var(--up)',
  successDark: 'var(--up)',
  successSoft: 'var(--up-soft)',
  successBorder: 'var(--up)',
  error: 'var(--down)',
  errorDark: 'var(--down)',
  errorSoft: 'var(--down-soft)',
  errorBorder: 'var(--down)',
  warning: 'var(--warning)',
  warningSoft: 'var(--warning-soft)',
  warningBorder: 'var(--warning)',
  overlay: 'var(--overlay)',
} as const;

export const radius = 8;
export const elevation = {
  card: 'none',
  overlay: '0 1px 1px rgba(16, 20, 28, 0.04), 0 16px 40px rgba(16, 20, 28, 0.12)',
};
export const motion = '200ms ease';
export const fontFamily = 'Inter, "Noto Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif';
export const numeric = { fontVariantNumeric: 'tabular-nums' as const };
export const safeArea = {
  top: 'env(safe-area-inset-top, 0px)',
  right: 'env(safe-area-inset-right, 0px)',
  bottom: 'env(safe-area-inset-bottom, 0px)',
  left: 'env(safe-area-inset-left, 0px)',
};
export const appBarHeight = 52;
export const mobileNavHeight = 64;
