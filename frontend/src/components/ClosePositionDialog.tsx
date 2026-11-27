import { useEffect, useRef, useState } from 'react';
import { useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { positionDirection } from '../displayData';
import { formatNumber, formatSide, formatUSDT, pnlColor } from '../formatters';
import { t, useLanguage } from '../i18n';
import { Alert } from './ui/alert';
import { Button } from './ui/button';
import { Dialog, DialogContent } from './ui/dialog';

type ClosePositionDialogProps = {
  open: boolean;
  position: Record<string, unknown> | null;
  loading: boolean;
  tradingMode?: TradingMode;
  onCancel: () => void;
  onConfirm: () => void;
};

export function ClosePositionDialog({ open, position, loading, tradingMode, onCancel, onConfirm }: ClosePositionDialogProps) {
  useLanguage();
  return (
    <Dialog open={open} onOpenChange={(next) => { if (!next && !loading) onCancel(); }}>
      <DialogContent title={`${t('确认平仓')} · ${tradingModeLabel(tradingMode)}`} className="w-[min(420px,calc(100vw-32px))]">
        {tradingMode === 'LIVE' && <Alert severity="error" className="mb-2">{t('此操作将平掉 OKX 实盘持仓，涉及真实资金。')}</Alert>}
        <Alert severity="warning">{t('将向 OKX 提交一键平仓请求，提交后请在 OKX 当前委托和持仓里确认最终成交。')}</Alert>
        <dl className="mt-3">
          <Row label={t('合约')} value={String(position?.instId ?? '-')} />
          <Row label={t('方向')} value={formatSide(position && positionDirection(position))} />
          <Row label={t('保证金模式')} value={formatSide(position?.marginMode)} />
          <Row label={t('数量')} value={formatNumber(position?.size)} />
          <Row label={t('真实保证金')} value={formatUSDT(position?.margin)} />
          <Row label={t('未实现盈亏')} value={formatUSDT(position?.unrealizedPnl)} color={pnlColor(position?.unrealizedPnl)} />
        </dl>
        <div className="mt-5 flex justify-end gap-2">
          <Button variant="ghost" onClick={onCancel} disabled={loading}>{t('取消')}</Button>
          <Button variant={tradingMode === 'LIVE' ? 'danger' : 'primary'} onClick={onConfirm}
            disabled={loading || !position || Boolean(tradingDisabledReason(tradingMode))}>{loading ? t('提交中') : t('确认平仓')}</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function Row({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <div className="flex items-baseline justify-between gap-4 border-t border-line py-2 text-[13px]">
      <dt className="text-muted">{label}</dt>
      <dd className="text-right font-medium tabular-nums" style={color ? { color } : undefined}>{value}</dd>
    </div>
  );
}

type TradingMode = 'LIVE' | 'DEMO' | 'DISABLED';

async function fetchTradingMode() {
  const status = await quantApi.accountBindingStatus() as { tradingMode?: TradingMode };
  const mode = status?.tradingMode;
  return mode === 'LIVE' || mode === 'DEMO' || mode === 'DISABLED' ? mode : undefined;
}

function tradingDisabledReason(mode?: TradingMode) {
  if (mode === 'LIVE' || mode === 'DEMO') return '';
  return mode === 'DISABLED' ? t('交易已禁用') : t('交易模式不可用');
}

export function tradingModeLabel(mode?: TradingMode) {
  if (mode === 'LIVE') return t('实盘交易');
  if (mode === 'DEMO') return t('模拟交易');
  return mode === 'DISABLED' ? t('交易已禁用') : t('未知');
}

export function useClosePosition(onClosed: () => Promise<void>) {
  const notify = useNotify();
  const [tradingMode, setTradingMode] = useState<TradingMode>();
  const [position, setPosition] = useState<Record<string, unknown> | null>(null);
  const [loading, setLoading] = useState(false);
  const submitting = useRef(false);

  async function refreshMode() {
    try {
      const mode = await fetchTradingMode();
      setTradingMode(mode);
      return mode;
    } catch {
      setTradingMode(undefined);
      return undefined;
    }
  }

  useEffect(() => { void refreshMode(); }, []);

  async function confirm() {
    if (!position || submitting.current || tradingDisabledReason(tradingMode)) return;
    const instId = String(position.instId ?? '');
    const posSide = String(positionDirection(position) ?? '').toLowerCase();
    if (!instId || (posSide !== 'long' && posSide !== 'short')) {
      notify(t('无法确认持仓方向'), { type: 'error' });
      return;
    }
    submitting.current = true;
    setLoading(true);
    try {
      const mode = await fetchTradingMode();
      if (mode !== tradingMode) {
        setTradingMode(mode);
        setPosition(null);
        notify(t('交易模式已变更，请重新确认'), { type: 'warning' });
        return;
      }
      await quantApi.closePosition({ instId, posSide, marginMode: String(position.marginMode ?? '') });
      notify(t('平仓请求已提交，请等待 OKX 最终状态'), { type: 'success' });
      setPosition(null);
      await onClosed();
    } catch (error) {
      notify(error instanceof Error ? t(error.message) : t('平仓提交失败'), { type: 'error', multiLine: true });
    } finally {
      submitting.current = false;
      setLoading(false);
    }
  }

  return {
    tradingMode,
    refreshMode,
    disabledReason: tradingDisabledReason(tradingMode),
    loading,
    select: setPosition,
    dialog: {
      open: Boolean(position),
      position,
      loading,
      tradingMode,
      onCancel: () => setPosition(null),
      onConfirm: () => void confirm(),
    } satisfies ClosePositionDialogProps,
  };
}

export function ClosePositionButton({ label = t('平仓'), disabledReason, loading, onClick }: {
  label?: string;
  disabledReason: string;
  loading: boolean;
  onClick: () => void;
}) {
  return (
    <span className="inline-block" title={disabledReason || undefined}>
      <Button variant="ghost" disabled={loading || Boolean(disabledReason)} onClick={onClick}>
        {loading ? t('提交中') : label}
      </Button>
      {disabledReason && <span className="block text-[12px] text-muted md:sr-only">{disabledReason}</span>}
    </span>
  );
}
