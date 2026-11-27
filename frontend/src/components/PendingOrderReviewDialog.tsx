import { useState, type ReactNode } from 'react';
import { formatAction, formatLeverage, formatNumber, formatPrice, formatSide } from '../formatters';
import { t, useLanguage } from '../i18n';
import { Alert } from './ui/alert';
import { Button } from './ui/button';
import { Dialog, DialogContent } from './ui/dialog';
import { Field, Input } from './ui/field';

type PendingOrderReviewDialogProps = {
  open: boolean;
  record: Record<string, unknown>;
  submitting?: boolean;
  tradingMode: string;
  onClose: () => void;
  onConfirm: (marginAmount: string) => void;
};

export function PendingOrderReviewDialog({ open, record, submitting, tradingMode, onClose, onConfirm }: PendingOrderReviewDialogProps) {
  useLanguage();
  const [marginAmount, setMarginAmount] = useState('');
  const amountValid = Number(marginAmount) > 0;
  const notional = amountValid ? Number(marginAmount) * Number(record.leverage ?? 1) : 0;
  const items: Array<[string, ReactNode]> = [
    [t('合约'), String(record.instId ?? '-')],
    [t('动作'), formatAction(record.action)],
    [t('方向'), `${formatSide(record.side)} / ${formatSide(record.posSide)}`],
    [t('订单类型'), formatSide(record.orderType)],
    [t('计划入场价'), formatPrice(record.price)],
    [t('杠杆'), formatLeverage(record.leverage)],
    [t('止损'), formatPrice(record.stopLossPrice)],
    [t('止盈'), formatPrice(record.takeProfitPrice)],
    [t('预估执行价'), formatPrice(record.estimatedEntryPrice)],
    [t('预估净盈亏比'), formatNumber(record.estimatedNetRiskRewardRatio, 2)],
    [t('最终目标毛盈亏比'), formatNumber(record.riskRewardRatio, 2)],
    [t('方案预估止损亏损（USDT）'), formatNumber(record.estimatedNetStopLossAmount, 4)],
    [t('信号评分'), formatNumber(record.signalScore, 0)],
    [t('资金费率'), formatNumber(record.fundingRate, 4)],
    [t('波动率'), formatNumber(record.volatility, 4)],
    [t('24h成交量'), formatNumber(record.volume24h, 0)],
    [t('状态'), String(record.status ?? '-')],
  ];

  return (
    <Dialog open={open} onOpenChange={(next) => { if (!next && !submitting) onClose(); }}>
      <DialogContent
        title={tradingMode === 'LIVE' ? t('确认提交 OKX LIVE 真实资金订单') : t('确认提交 OKX DEMO 模拟订单')}
        className="w-[min(640px,calc(100vw-32px))]"
      >
        <Field label={t('本次投入保证金 USDT')} hint={t('确认时会按实时盘口或限价重新核算预计亏损、费用和可下张数。')}>
          <Input type="number" inputMode="decimal" min={0} step="0.01" value={marginAmount} onChange={(event) => setMarginAmount(event.target.value)} />
        </Field>
        {amountValid ? (
          <Alert className="mt-3" severity="warning">
            {t('预计名义仓位约')} {formatNumber(notional, 2)} {t('USDT。提交成功只代表OKX已接收委托，成交、账单和持仓以OKX为准。')}
          </Alert>
        ) : null}
        <dl className="mt-4 grid gap-x-6 sm:grid-cols-2">
          {items.map(([label, value]) => (
            <div key={label} className="flex items-baseline justify-between gap-3 border-t border-line py-2 text-[13px]">
              <dt className="text-muted">{label}</dt>
              <dd className="text-right font-medium">{value}</dd>
            </div>
          ))}
        </dl>
        <div className="mt-5 flex flex-wrap justify-end gap-2">
          <Button variant="ghost" onClick={onClose} disabled={submitting}>{t('返回')}</Button>
          <Button
            variant={tradingMode === 'LIVE' ? 'danger' : 'primary'}
            onClick={() => onConfirm(marginAmount)}
            disabled={submitting || !amountValid || tradingMode === 'DISABLED'}
          >
            {tradingMode === 'LIVE' ? t('确认 LIVE 真实资金提交') : t('确认 DEMO 模拟提交')}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
