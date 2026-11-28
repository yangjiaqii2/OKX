import { useEffect, useState } from 'react';
import { useNotify } from 'react-admin';
import { getAuthRole } from '../api/auth';
import { quantApi } from '../api/quantApi';
import { PageHeader, PageShell } from '../components/PageShell';
import { PanelState } from '../components/PanelState';
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
  const [status, setStatus] = useState<SystemStatus>();
  const [statusLoading, setStatusLoading] = useState(true);
  const [statusError, setStatusError] = useState('');
  const [forbidden, setForbidden] = useState(false);
  const [autoTradeMarginUsdt, setAutoTradeMarginUsdt] = useState('');
  const [autoTradeBudgetMode, setAutoTradeBudgetMode] = useState<AutoTradeBudgetMode>();
  const [autoTradeRiskMode, setAutoTradeRiskMode] = useState<AutoTradeRiskMode>();
  const [noRiskMinScore, setNoRiskMinScore] = useState('');
  const [autoTradeMinLeverage, setAutoTradeMinLeverage] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const isAdmin = getAuthRole() === 'ADMIN' && !forbidden;
  const disabled = !status || statusLoading || submitting;
  const locked = disabled || status?.autoTradeEnabled === true;
  const canEnable = !locked && status?.emergencyStop === false && Boolean(autoTradeBudgetMode && autoTradeRiskMode);

  function reportError(error: unknown, fallback: string) {
    const denied = (error as { status?: number } | null)?.status === 403;
    if (denied) setForbidden(true);
    const message = denied ? t('无权限，仅管理员可以操作系统控制。') : error instanceof Error ? error.message : fallback;
    notify(message, { type: 'error' });
    return message;
  }

  async function loadStatus() {
    setStatusLoading(true);
    setStatusError('');
    try {
      const nextStatus = (await quantApi.systemStatus()) as SystemStatus;
      if (typeof nextStatus?.emergencyStop !== 'boolean' || typeof nextStatus.autoTradeEnabled !== 'boolean') {
        throw new Error(t('系统状态加载失败'));
      }
      setStatus(nextStatus);
      const budgetValue = nextStatus.autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT'
        ? Number(nextStatus.autoTradeMarginPct ?? 0)
        : Number(nextStatus.autoTradeMarginUsdt ?? 0);
      setAutoTradeMarginUsdt(Number.isFinite(budgetValue) && budgetValue > 0 ? String(budgetValue) : '');
      if (nextStatus.autoTradeBudgetMode === 'FIXED_USDT' || nextStatus.autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT') setAutoTradeBudgetMode(nextStatus.autoTradeBudgetMode);
      if (nextStatus.autoTradeRiskMode === 'STRICT' || nextStatus.autoTradeRiskMode === 'NO_RISK') setAutoTradeRiskMode(nextStatus.autoTradeRiskMode);
      const score = Number(nextStatus.noRiskMinScore ?? 0);
      if (Number.isFinite(score) && score >= 60 && score <= 100) setNoRiskMinScore(String(score));
      const minLeverage = Number(nextStatus.autoTradeMinLeverage ?? 0);
      if (Number.isInteger(minLeverage) && minLeverage >= 1 && minLeverage <= 20) setAutoTradeMinLeverage(String(minLeverage));
    } catch (error) {
      setStatus(undefined);
      setStatusError(reportError(error, t('系统状态加载失败')));
    } finally {
      setStatusLoading(false);
    }
  }

  async function emergencyStop() {
    if (!isAdmin || disabled || status?.emergencyStop !== false) return;
    setSubmitting(true);
    try {
      setStatus((await quantApi.emergencyStop()) as SystemStatus);
      notify(t('已触发紧急停止'), { type: 'warning' });
    } catch (error) {
      reportError(error, t('紧急停止失败'));
    } finally {
      setSubmitting(false);
      setEmergencyOpen(false);
    }
  }

  async function resume() {
    if (!isAdmin || disabled || status?.emergencyStop !== true) return;
    setSubmitting(true);
    try {
      setStatus((await quantApi.resume()) as SystemStatus);
      notify(t('系统已恢复运行'), { type: 'success' });
    } catch (error) {
      reportError(error, t('恢复失败'));
    } finally {
      setSubmitting(false);
      setResumeOpen(false);
    }
  }

  async function enableAutoTrade() {
    if (!isAdmin || !canEnable || !autoTradeRiskMode || !autoTradeBudgetMode) return;
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
      reportError(error, t('自动交易开启失败'));
    } finally {
      setSubmitting(false);
      setAutoEnableOpen(false);
    }
  }

  async function disableAutoTrade() {
    if (!isAdmin || disabled || status?.autoTradeEnabled !== true) return;
    setSubmitting(true);
    try {
      setStatus((await quantApi.disableAutoTrade()) as SystemStatus);
      notify(t('自动交易已关闭'), { type: 'success' });
    } catch (error) {
      reportError(error, t('自动交易关闭失败'));
    } finally {
      setSubmitting(false);
      setAutoDisableOpen(false);
    }
  }

  useEffect(() => { if (isAdmin) void loadStatus(); }, [isAdmin]);

  if (!isAdmin) {
    return <PageShell title={t('系统控制')} maxWidth={760}>
      <PageHeader title={t('系统控制')} />
      <Alert className="mt-5" severity="warning">{t('无权限，仅管理员可以操作系统控制。')}</Alert>
    </PageShell>;
  }

  const updatedAt = status?.updatedAt ? new Date(status.updatedAt) : null;

  return (
    <PageShell title={t('系统控制')} maxWidth={760}>
      <PageHeader title={t('系统控制')} subtitle={t('集中放置影响交易流程的系统级动作。')} />
      <Alert className="mt-5" severity="warning">{t('紧急停止不会删除数据，但会切换后端运行状态。实盘环境下请先确认当前OKX未留有未处理订单。')}</Alert>
      {statusLoading && <PanelState loading />}
      {statusError && (
        <Alert className="mt-4" severity="error">
          {statusError} <Button onClick={() => void loadStatus()}>{t('重试')}</Button>
        </Alert>
      )}
      <section className="mt-6" aria-busy={statusLoading}>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-[15px] font-semibold">{t('运行开关')}</h2>
          {status && <div className="flex flex-wrap gap-2 text-[12px] font-medium">
            <span className={status.emergencyStop ? 'text-down' : 'text-up'}>{status.emergencyStop ? t('紧急停止') : t('运行中')}</span>
            <span className="text-muted">{status.autoTradeEnabled ? status.autoTradeRiskMode === 'NO_RISK' ? t('Shadow 运行中') : t('自动交易开启') : t('自动交易关闭')}</span>
            <span className="text-muted">{status.autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT' ? `${t('动态预算 ')}${status.autoTradeMarginPct ?? '--'}%` : `${t('固定预算 ')}${usdtAmount(status.autoTradeMarginUsdt)} USDT`}</span>
            <span className="text-muted">{status.autoTradeRiskMode === 'NO_RISK' ? t('Shadow 观察') : t('严格风控')}</span>
            <span className="text-muted">{t('最低杠杆 ')}{status.autoTradeMinLeverage ?? autoTradeMinLeverage}x</span>
          </div>}
        </div>
        {updatedAt && !Number.isNaN(updatedAt.getTime()) && (
          <p className="mt-2 text-[12px] text-muted">{t('最后更新时间')} {updatedAt.toLocaleString(getLanguage())}</p>
        )}
        <div className="mt-4 flex flex-wrap gap-2">
          <Button variant="danger" disabled={disabled || status?.emergencyStop !== false} onClick={() => setEmergencyOpen(true)}>{t('紧急停止')}</Button>
          <Button variant="ghost" disabled={disabled || status?.emergencyStop !== true} onClick={() => setResumeOpen(true)}>{t('恢复运行')}</Button>
        </div>
        <div className="mt-6 flex flex-wrap items-end gap-3">
          <Field className="w-full sm:w-52" label={autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT' ? t('可用余额比例') : t('自动交易总预算')}>
            <Input disabled={locked} inputMode="decimal" value={autoTradeMarginUsdt} onChange={(event) => setAutoTradeMarginUsdt(event.target.value.replace(/[^\d.]/g, ''))} />
          </Field>
          <Segment<AutoTradeBudgetMode> value={autoTradeBudgetMode} disabled={locked} options={[['FIXED_USDT', t('固定金额')], ['AVAILABLE_BALANCE_PERCENT', t('余额比例')]]} onChange={setAutoTradeBudgetMode} />
          <Segment<AutoTradeRiskMode> value={autoTradeRiskMode} disabled={locked} options={[['STRICT', t('严格风控')], ['NO_RISK', t('Shadow 观察')]]} onChange={setAutoTradeRiskMode} />
          {autoTradeRiskMode === 'NO_RISK' ? (
            <Field className="w-full sm:w-36" label={t('Shadow 最低分')}>
              <Input disabled={locked} inputMode="numeric" value={noRiskMinScore} onChange={(event) => setNoRiskMinScore(event.target.value.replace(/[^\d]/g, '').slice(0, 3))} />
            </Field>
          ) : null}
          <Field className="w-full sm:w-32" label={t('最低杠杆')}>
            <Input disabled={locked} inputMode="numeric" value={autoTradeMinLeverage} onChange={(event) => setAutoTradeMinLeverage(event.target.value.replace(/[^\d]/g, '').slice(0, 2))} />
          </Field>
          <Button variant="warning" disabled={!canEnable} onClick={() => setAutoEnableOpen(true)}>
            {autoTradeRiskMode === 'NO_RISK' ? t('开启 Shadow') : t('开启自动交易')}
          </Button>
          <Button variant="ghost" disabled={disabled || !status?.autoTradeEnabled} onClick={() => setAutoDisableOpen(true)}>{t('关闭自动交易')}</Button>
        </div>
      </section>
      <AdminAgentPlatformSection onEmergencyTriggered={() => void loadStatus()} />
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

function Segment<T extends string>({ value, options, onChange, disabled }: { value?: T; options: Array<[T, string]>; onChange: (value: T) => void; disabled?: boolean }) {
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

type AdminOverviewData = {
  totalAgents: number;
  runningAgents: number;
  activeUsers: number;
  emergencyStopped: boolean;
  wsSessions: Array<{ username: string; state: string; reason: string }>;
  agents: Array<{ id: number; userName: string; name: string; status: string; runMode: string; lastDecision?: string }>;
};

function AdminAgentPlatformSection({ onEmergencyTriggered }: { onEmergencyTriggered: () => void }) {
  const notify = useNotify();
  const [overview, setOverview] = useState<AdminOverviewData | null>(null);
  const [pausing, setPausing] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);

  async function loadOverview() {
    try {
      const data = (await quantApi.adminAgentOverview()) as AdminOverviewData;
      setOverview(data);
    } catch {
      // Non-critical admin widget.
    }
  }

  useEffect(() => {
    void loadOverview();
  }, []);

  async function pauseAll() {
    setPausing(true);
    try {
      const res = (await quantApi.adminPauseAllAgents()) as { pausedCount?: number; overview?: AdminOverviewData };
      if (res.overview) setOverview(res.overview);
      notify(`${t('已暂停全平台活跃 Agent：')}${res.pausedCount ?? 0}`, { type: 'warning' });
      onEmergencyTriggered();
    } catch (error) {
      notify(error instanceof Error ? error.message : t('全局暂停失败'), { type: 'error' });
    } finally {
      setPausing(false);
      setConfirmOpen(false);
    }
  }

  if (!overview) return null;

  return (
    <section className="mt-8 rounded-[22px] border border-line bg-raised p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-[15px] font-semibold text-ink">{t('Agent 平台总览与全局急停')}</h2>
          <p className="mt-0.5 text-[12px] text-muted">
            {t('实时监控所有注册用户的活跃交易 Agent 与私有 WebSocket 连接池。')}
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" onClick={() => void loadOverview()}>{t('刷新')}</Button>
          <Button
            variant="danger"
            size="sm"
            disabled={pausing || overview.runningAgents === 0}
            onClick={() => setConfirmOpen(true)}
          >
            {t('暂停全平台 Agent')}
          </Button>
        </div>
      </div>

      <div className="mt-4 grid grid-cols-3 gap-4 rounded-[16px] bg-canvas p-3.5 text-[13px]">
        <div>
          <div className="text-[11px] text-muted">{t('平台总 Agent')}</div>
          <div className="mt-0.5 font-semibold text-ink">{overview.totalAgents}</div>
        </div>
        <div>
          <div className="text-[11px] text-muted">{t('运行中 Agent')}</div>
          <div className="mt-0.5 font-semibold text-up">{overview.runningAgents}</div>
        </div>
        <div>
          <div className="text-[11px] text-muted">{t('活跃用户 / WS 会话')}</div>
          <div className="mt-0.5 font-semibold text-ink">{overview.activeUsers} / {overview.wsSessions.length}</div>
        </div>
      </div>

      {overview.wsSessions.length > 0 ? (
        <div className="mt-3 flex flex-wrap gap-2">
          {overview.wsSessions.map((ws) => (
            <span key={ws.username} className="rounded-full border border-line bg-canvas px-2.5 py-0.5 text-[11px] text-muted">
              {ws.username} · WS {ws.state}
            </span>
          ))}
        </div>
      ) : null}

      <ConfirmDialog
        open={confirmOpen}
        title={t('暂停全平台所有活跃 Agent')}
        content={t('确认后将立即暂停所有用户的 RUNNING 状态 Agent 并开启系统紧急停止。')}
        confirmLabel={t('确认暂停')}
        cancelLabel={t('返回')}
        tone="danger"
        pending={pausing}
        onConfirm={() => void pauseAll()}
        onClose={() => setConfirmOpen(false)}
      />
    </section>
  );
}
