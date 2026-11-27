import { useEffect, useState } from 'react';
import { useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { PageHeader, PageShell } from '../components/PageShell';
import { Alert } from '../components/ui/alert';
import { Button } from '../components/ui/button';
import { ConfirmDialog } from '../components/ui/confirm-dialog';
import { Field, Input } from '../components/ui/field';
import { cn } from '../lib/cn';
import { getLanguage, t, useLanguage } from '../i18n';

type SystemStatus = {
  emergencyStop?: boolean;
  autoTradeEnabled?: boolean;
  autoTradeMarginUsdt?: number | string;
  autoTradeBudgetMode?: AutoTradeBudgetMode;
  autoTradeMarginPct?: number | string;
  autoTradeRiskMode?: AutoTradeRiskMode;
  noRiskMinScore?: number | string;
  autoTradeMinLeverage?: number | string;
  updatedAt?: string;
};
type AutoTradeRiskMode = 'STRICT' | 'NO_RISK';
type AutoTradeBudgetMode = 'FIXED_USDT' | 'AVAILABLE_BALANCE_PERCENT';

function usdtAmount(value: unknown) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number.toLocaleString(getLanguage(), { maximumFractionDigits: 2 }) : '--';
}

export function SystemControlPage() {
  useLanguage();
  const notify = useNotify();
  const [emergencyOpen, setEmergencyOpen] = useState(false);
  const [resumeOpen, setResumeOpen] = useState(false);
  const [autoEnableOpen, setAutoEnableOpen] = useState(false);
  const [autoDisableOpen, setAutoDisableOpen] = useState(false);
  const [status, setStatus] = useState<SystemStatus>({});
  const [autoTradeMarginUsdt, setAutoTradeMarginUsdt] = useState('100');
  const [autoTradeBudgetMode, setAutoTradeBudgetMode] = useState<AutoTradeBudgetMode>('FIXED_USDT');
  const [autoTradeRiskMode, setAutoTradeRiskMode] = useState<AutoTradeRiskMode>('STRICT');
  const [noRiskMinScore, setNoRiskMinScore] = useState('70');
  const [autoTradeMinLeverage, setAutoTradeMinLeverage] = useState('1');
  const [submitting, setSubmitting] = useState(false);

  async function loadStatus() {
    try {
      const nextStatus = (await quantApi.systemStatus()) as SystemStatus;
      setStatus(nextStatus);
      const budgetValue = nextStatus.autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT'
        ? Number(nextStatus.autoTradeMarginPct ?? 0)
        : Number(nextStatus.autoTradeMarginUsdt ?? 0);
      if (Number.isFinite(budgetValue) && budgetValue > 0) setAutoTradeMarginUsdt(String(budgetValue));
      if (nextStatus.autoTradeBudgetMode === 'FIXED_USDT' || nextStatus.autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT') setAutoTradeBudgetMode(nextStatus.autoTradeBudgetMode);
      if (nextStatus.autoTradeRiskMode === 'STRICT' || nextStatus.autoTradeRiskMode === 'NO_RISK') setAutoTradeRiskMode(nextStatus.autoTradeRiskMode);
      const score = Number(nextStatus.noRiskMinScore ?? 0);
      if (Number.isFinite(score) && score >= 60 && score <= 100) setNoRiskMinScore(String(score));
      const minLeverage = Number(nextStatus.autoTradeMinLeverage ?? 0);
      if (Number.isInteger(minLeverage) && minLeverage >= 1 && minLeverage <= 20) setAutoTradeMinLeverage(String(minLeverage));
    } catch (error) {
      notify(error instanceof Error ? error.message : t('系统状态加载失败'), { type: 'error' });
    }
  }

  async function emergencyStop() {
    setSubmitting(true);
    try {
      setStatus((await quantApi.emergencyStop()) as SystemStatus);
      notify(t('已触发紧急停止'), { type: 'warning' });
    } catch (error) {
      notify(error instanceof Error ? error.message : t('紧急停止失败'), { type: 'error' });
    } finally {
      setSubmitting(false);
      setEmergencyOpen(false);
    }
  }

  async function resume() {
    setSubmitting(true);
    try {
      setStatus((await quantApi.resume()) as SystemStatus);
      notify(t('系统已恢复运行'), { type: 'success' });
    } catch (error) {
      notify(error instanceof Error ? error.message : t('恢复失败'), { type: 'error' });
    } finally {
      setSubmitting(false);
      setResumeOpen(false);
    }
  }

  async function enableAutoTrade() {
    const budgetValue = Number(autoTradeMarginUsdt);
    const validBudget = autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT'
      ? Number.isFinite(budgetValue) && budgetValue > 0 && budgetValue <= 100
      : Number.isFinite(budgetValue) && budgetValue > 0;
    if (!validBudget) {
      notify(autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT' ? t('可用余额百分比必须大于0且不超过100%') : t('自动交易总预算必须大于0'), { type: 'warning' });
      return;
    }
    const score = Number(noRiskMinScore);
    if (autoTradeRiskMode === 'NO_RISK' && (!Number.isInteger(score) || score < 60 || score > 100)) {
      notify(t('Shadow 最低观察分数必须是60到100之间的整数'), { type: 'warning' });
      return;
    }
    const minLeverage = Number(autoTradeMinLeverage);
    if (!Number.isInteger(minLeverage) || minLeverage < 1 || minLeverage > 20) {
      notify(t('自动交易最低杠杆倍数必须是1到20之间的整数'), { type: 'warning' });
      return;
    }
    setSubmitting(true);
    try {
      setStatus((await quantApi.enableAutoTrade(String(budgetValue), autoTradeRiskMode, autoTradeRiskMode === 'NO_RISK' ? String(score) : undefined, String(minLeverage), autoTradeBudgetMode)) as SystemStatus);
      notify(autoTradeRiskMode === 'NO_RISK'
        ? `${t('Shadow 观察已开启：最低分 ')}${score}${t('，不会创建或提交订单')}`
        : autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT'
          ? `${t('自动交易已开启：使用可用余额 ')}${budgetValue}${t('% 动态预算，严格风控')}`
          : `${t('自动交易已开启：总预算 ')}${budgetValue}${t(' USDT，严格风控')}`, { type: 'warning' });
    } catch (error) {
      notify(error instanceof Error ? error.message : t('自动交易开启失败'), { type: 'error' });
    } finally {
      setSubmitting(false);
      setAutoEnableOpen(false);
    }
  }

  async function disableAutoTrade() {
    setSubmitting(true);
    try {
      setStatus((await quantApi.disableAutoTrade()) as SystemStatus);
      notify(t('自动交易已关闭'), { type: 'success' });
    } catch (error) {
      notify(error instanceof Error ? error.message : t('自动交易关闭失败'), { type: 'error' });
    } finally {
      setSubmitting(false);
      setAutoDisableOpen(false);
    }
  }

  useEffect(() => { void loadStatus(); }, []);
  const locked = submitting || Boolean(status.autoTradeEnabled);

  return (
    <PageShell title={t('系统控制')} maxWidth={760}>
      <PageHeader title={t('系统控制')} subtitle={t('集中放置影响交易流程的系统级动作。')} />
      <Alert className="mt-5" severity="warning">{t('紧急停止不会删除数据，但会切换后端运行状态。实盘环境下请先确认当前OKX未留有未处理订单。')}</Alert>
      <section className="mt-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-[15px] font-semibold">{t('运行开关')}</h2>
          <div className="flex flex-wrap gap-2 text-[12px] font-medium">
            <span className={status.emergencyStop ? 'text-down' : 'text-up'}>{status.emergencyStop ? t('紧急停止') : t('运行中')}</span>
            <span className="text-muted">{status.autoTradeEnabled ? status.autoTradeRiskMode === 'NO_RISK' ? t('Shadow 运行中') : t('自动交易开启') : t('自动交易关闭')}</span>
            <span className="text-muted">{status.autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT' ? `${t('动态预算 ')}${status.autoTradeMarginPct ?? '--'}%` : `${t('固定预算 ')}${usdtAmount(status.autoTradeMarginUsdt)} USDT`}</span>
            <span className="text-muted">{status.autoTradeRiskMode === 'NO_RISK' ? t('Shadow 观察') : t('严格风控')}</span>
            <span className="text-muted">{t('最低杠杆 ')}{status.autoTradeMinLeverage ?? autoTradeMinLeverage}x</span>
          </div>
        </div>
        <div className="mt-4 flex flex-wrap gap-2">
          <Button variant="danger" disabled={submitting} onClick={() => setEmergencyOpen(true)}>{t('紧急停止')}</Button>
          <Button variant="ghost" disabled={submitting} onClick={() => setResumeOpen(true)}>{t('恢复运行')}</Button>
        </div>
        <div className="mt-6 flex flex-wrap items-end gap-3">
          <Field className="w-full sm:w-52" label={autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT' ? t('可用余额比例') : t('自动交易总预算')}>
            <Input disabled={locked} inputMode="decimal" value={autoTradeMarginUsdt} onChange={(event) => setAutoTradeMarginUsdt(event.target.value.replace(/[^\d.]/g, ''))} />
          </Field>
          <Segment value={autoTradeBudgetMode} disabled={locked} options={[['FIXED_USDT', t('固定金额')], ['AVAILABLE_BALANCE_PERCENT', t('余额比例')]]} onChange={setAutoTradeBudgetMode} />
          <Segment value={autoTradeRiskMode} disabled={locked} options={[['STRICT', t('严格风控')], ['NO_RISK', t('Shadow 观察')]]} onChange={setAutoTradeRiskMode} />
          {autoTradeRiskMode === 'NO_RISK' ? (
            <Field className="w-full sm:w-36" label={t('Shadow 最低分')}>
              <Input disabled={locked} inputMode="numeric" value={noRiskMinScore} onChange={(event) => setNoRiskMinScore(event.target.value.replace(/[^\d]/g, '').slice(0, 3))} />
            </Field>
          ) : null}
          <Field className="w-full sm:w-32" label={t('最低杠杆')}>
            <Input disabled={locked} inputMode="numeric" value={autoTradeMinLeverage} onChange={(event) => setAutoTradeMinLeverage(event.target.value.replace(/[^\d]/g, '').slice(0, 2))} />
          </Field>
          <Button variant="warning" disabled={submitting || Boolean(status.emergencyStop) || Boolean(status.autoTradeEnabled)} onClick={() => setAutoEnableOpen(true)}>
            {autoTradeRiskMode === 'NO_RISK' ? t('开启 Shadow') : t('开启自动交易')}
          </Button>
          <Button variant="ghost" disabled={submitting || !status.autoTradeEnabled} onClick={() => setAutoDisableOpen(true)}>{t('关闭自动交易')}</Button>
        </div>
      </section>
      <ConfirmDialog open={emergencyOpen} title={t('触发紧急停止')} content={t('确认后后端会切换到紧急停止状态。请先确认当前 OKX 没有未处理订单。')} confirmLabel={t('紧急停止')} cancelLabel={t('返回')} tone="danger" pending={submitting} onConfirm={() => void emergencyStop()} onClose={() => setEmergencyOpen(false)} />
      <ConfirmDialog open={resumeOpen} title={t('恢复系统运行')} content={t('确认恢复后，扫描和交易流程将重新按后端配置运行。')} confirmLabel={t('恢复运行')} cancelLabel={t('返回')} pending={submitting} onConfirm={() => void resume()} onClose={() => setResumeOpen(false)} />
      <ConfirmDialog
        open={autoEnableOpen}
        title={autoTradeRiskMode === 'NO_RISK' ? t('开启 Shadow 观察') : t('开启自动交易')}
        content={[
          autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT'
            ? t('确认后，每轮使用最新可用余额的 {value}% 作为自动交易预算。', { value: autoTradeMarginUsdt || '--' })
            : t('确认后，使用 {value} USDT 作为自动交易总预算。', { value: autoTradeMarginUsdt || '--' }),
          autoTradeRiskMode === 'NO_RISK'
            ? t('Shadow 观察模式记录 {score} 分及以上候选的策略输出，交易订单不会被创建或提交。', { score: noRiskMinScore || '--' })
            : t('严格风控模式保留当前 AUTO_TRADE_ALLOWED 门槛。'),
        ].join(' ')}
        confirmLabel={t('开启')}
        cancelLabel={t('返回')}
        pending={submitting}
        onConfirm={() => void enableAutoTrade()}
        onClose={() => setAutoEnableOpen(false)}
      />
      <ConfirmDialog open={autoDisableOpen} title={t('关闭自动交易')} content={t('确认后，后端不会再自动提交新订单，已提交的 OKX 订单需要继续在 OKX 或持仓页检查。')} confirmLabel={t('关闭')} cancelLabel={t('返回')} pending={submitting} onConfirm={() => void disableAutoTrade()} onClose={() => setAutoDisableOpen(false)} />
    </PageShell>
  );
}

function Segment<T extends string>({ value, options, onChange, disabled }: { value: T; options: Array<[T, string]>; onChange: (value: T) => void; disabled?: boolean }) {
  return (
    <div className="flex h-12 items-center rounded-lg bg-sunken p-0.5">
      {options.map(([option, label]) => (
        <button key={option} type="button" disabled={disabled} className={cn('h-10 rounded-md px-3 text-[13px] font-medium disabled:opacity-50', value === option ? 'bg-field text-ink' : 'text-muted')} onClick={() => onChange(option)}>
          {label}
        </button>
      ))}
    </div>
  );
}
