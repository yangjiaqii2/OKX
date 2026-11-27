import { formatNumber, formatSide, formatUSDT, pnlColor } from '../formatters';
import { t, useLanguage } from '../i18n';
import { Alert } from './ui/alert';
import { Button } from './ui/button';
import { Dialog, DialogContent } from './ui/dialog';

type ClosePositionDialogProps = {
  open: boolean;
  position: Record<string, unknown> | null;
  loading: boolean;
  onCancel: () => void;
  onConfirm: () => void;
};

export function ClosePositionDialog({ open, position, loading, onCancel, onConfirm }: ClosePositionDialogProps) {
  useLanguage();
  return (
    <Dialog open={open} onOpenChange={(next) => { if (!next && !loading) onCancel(); }}>
      <DialogContent title={t('确认平仓')} className="w-[min(420px,calc(100vw-32px))]">
        <Alert severity="warning">{t('将向 OKX 提交一键平仓请求，提交后请在 OKX 当前委托和持仓里确认最终成交。')}</Alert>
        <dl className="mt-3">
          <Row label={t('合约')} value={String(position?.instId ?? '-')} />
          <Row label={t('方向')} value={formatSide(position?.posSide)} />
          <Row label={t('保证金模式')} value={formatSide(position?.marginMode)} />
          <Row label={t('数量')} value={formatNumber(position?.size)} />
          <Row label={t('真实保证金')} value={formatUSDT(position?.margin)} />
          <Row label={t('未实现盈亏')} value={formatUSDT(position?.unrealizedPnl)} color={pnlColor(position?.unrealizedPnl)} />
        </dl>
        <div className="mt-5 flex justify-end gap-2">
          <Button variant="ghost" onClick={onCancel} disabled={loading}>{t('取消')}</Button>
          <Button variant="danger" onClick={onConfirm} disabled={loading || !position}>{loading ? t('提交中') : t('确认平仓')}</Button>
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
