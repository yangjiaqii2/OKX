import { useEffect, useRef, useState } from 'react';
import { useNotify } from 'react-admin';
import { Link } from 'react-router-dom';
import { quantApi } from '../api/quantApi';
import { PageHeader, PageShell } from '../components/PageShell';
import { PanelState } from '../components/PanelState';
import { PositionSnapshotTable } from '../components/PositionSnapshotTable';
import { Alert } from '../components/ui/alert';
import { hasAccountBalance, openPositions, POLL_INTERVAL_MS } from '../displayData';
import { formatLeverage, formatNumber, formatPercent, formatPrice, formatStatus, formatUSDT, pnlColor } from '../formatters';
import { cn } from '../lib/cn';
import { getLanguage, t, useLanguage } from '../i18n';

type DashboardState = {
  risk?: Record<string, unknown>;
  account?: Record<string, unknown>;
  profit?: Record<string, unknown>;
  contracts: unknown[];
  orders: unknown[];
  positions: Record<string, unknown>[];
  positionsError?: string;
  contractsError?: string;
  ordersError?: string;
  profitError?: string;
  fxRate?: Record<string, unknown>;
  fxRateError?: string;
};

export function Dashboard() {
  useLanguage();
  const notify = useNotify();
  const [loading, setLoading] = useState(true);
  const [assetCurrency, setAssetCurrency] = useState<'USDT' | 'RMB'>('USDT');
  const [state, setState] = useState<DashboardState>({ contracts: [], orders: [], positions: [] });

  const loadRunning = useRef(false);
  const [refreshing, setRefreshing] = useState(false);

  async function load(source: 'initial' | 'poll' | 'manual') {
    if (loadRunning.current) return;
    loadRunning.current = true;
    if (source === 'manual') setRefreshing(true);
    try {
      const [riskResult, accountResult, profitResult, contractResult, orderResult, fxRateResult, positionResult] = await Promise.allSettled([
        quantApi.riskStatus(),
        quantApi.accountSummary(),
        quantApi.autoTradeProfitSummary(),
        quantApi.contractCandidates(),
        quantApi.pendingOrders(),
        quantApi.fxRate('USD', 'CNY'),
        quantApi.positions(),
      ]);
      const positionData = positionResult.status === 'fulfilled' ? positionResult.value : undefined;
      const positions = Array.isArray(positionData) ? openPositions(positionData) : [];
      const positionsError = positionResult.status === 'rejected'
        ? (positionResult.reason instanceof Error ? positionResult.reason.message : t('持仓接口调用失败'))
        : Array.isArray(positionData) ? '' : t('持仓响应格式异常');
      const contractsError = contractResult.status === 'rejected'
        ? (contractResult.reason instanceof Error ? contractResult.reason.message : t('合约机会池加载失败'))
        : Array.isArray(contractResult.value) ? '' : t('合约机会响应格式异常');
      const ordersError = orderResult.status === 'rejected'
        ? (orderResult.reason instanceof Error ? orderResult.reason.message : t('待确认订单加载失败'))
        : Array.isArray(orderResult.value) ? '' : t('待确认订单响应格式异常');
      const profitError = profitResult.status === 'rejected'
        ? (profitResult.reason instanceof Error ? profitResult.reason.message : t('自动交易收益加载失败'))
        : '';
      const fxRateError = fxRateResult.status === 'rejected'
        ? (fxRateResult.reason instanceof Error ? fxRateResult.reason.message : t('汇率加载失败'))
        : '';
      const fxRate = fxRateResult.status === 'fulfilled' ? fxRateResult.value as Record<string, unknown> : undefined;
      if (fxRateError || !Number.isFinite(Number(fxRate?.rate)) || Number(fxRate?.rate) <= 0) setAssetCurrency('USDT');
      setState({
        risk: riskResult.status === 'fulfilled' ? riskResult.value as Record<string, unknown> : {},
        account: accountResult.status === 'fulfilled' ? accountResult.value as Record<string, unknown> : {},
        profit: profitResult.status === 'fulfilled' ? profitResult.value as Record<string, unknown> : {},
        fxRate,
        contracts: contractResult.status === 'fulfilled' && Array.isArray(contractResult.value) ? contractResult.value : [],
        orders: orderResult.status === 'fulfilled' && Array.isArray(orderResult.value) ? orderResult.value : [],
        positions,
        positionsError,
        contractsError,
        ordersError,
        profitError,
        fxRateError,
      });
      if (source !== 'poll' && (riskResult.status === 'rejected' || accountResult.status === 'rejected')) {
        notify(t('核心账户或风控数据加载失败，请检查后端服务。'), { type: 'warning' });
      }
    } catch (error) {
      if (source !== 'poll') notify(error instanceof Error ? error.message : t('加载总览失败'), { type: 'error' });
    } finally {
      loadRunning.current = false;
      setLoading(false);
      setRefreshing(false);
    }
  }

  useEffect(() => {
    let timer: number | undefined;
    function schedule() {
      window.clearInterval(timer);
      if (document.visibilityState === 'visible') {
        timer = window.setInterval(() => { void load('poll'); }, POLL_INTERVAL_MS);
      }
    }
    function onVisibilityChange() {
      schedule();
      if (document.visibilityState === 'visible') void load('poll');
    }
    void load('initial');
    schedule();
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, []);

  if (loading) {
    return (
      <PageShell title={t('总览')}>
        <PageHeader title={t('总览')} subtitle={t('正在同步 OKX 账户、风险状态、候选合约和待确认订单。')} />
        <PanelState loading className="mt-8" />
      </PageShell>
    );
  }

  const accountMode = String(state.account?.mode ?? 'UNKNOWN');
  const balanceAvailable = hasAccountBalance(accountMode);
  const availableBalance = state.account?.availableBalance;
  const positionCount = state.positionsError || accountMode === 'OKX_UNBOUND' ? null : state.positions.length;
  const pendingCount = state.ordersError ? null : state.orders.length;
  const topContracts = state.contracts.slice(0, 4) as Record<string, unknown>[];
  const fxRateAvailable = Number.isFinite(Number(state.fxRate?.rate)) && Number(state.fxRate?.rate) > 0;
  const formatAsset = (value: unknown) => formatAssetMoney(value, assetCurrency, state.fxRate);
  const unrealizedPnl = state.profit?.unrealizedPnlUsdt;
  const realizedPnl = state.profit?.realizedPnlUsdt;
  const risk = state.risk ?? {};

  return (
    <PageShell title={t('总览')}>
      <PageHeader
        title={t('总览')}
        subtitle={t('OKX 合约信号进入待确认后执行。账户与风控优先于下单。')}
        actions={
          <div className="flex rounded-lg bg-[#E7EAF0] p-0.5 dark:bg-sunken">
            {(['USDT', 'RMB'] as const).map((value) => (
              <button
                key={value}
                type="button"
                disabled={value === 'RMB' && !fxRateAvailable}
                className={cn('h-[26px] min-w-12 rounded-md px-2.5 font-sans text-[12px] font-medium', assetCurrency === value ? 'bg-field text-ink shadow-[0_1px_1px_rgba(23,26,33,0.04),0_0_0_1px_rgba(23,26,33,0.04)]' : 'text-muted')}
                onClick={() => {
                  if (value === 'RMB' && !fxRateAvailable) {
                    setAssetCurrency('USDT');
                    notify(t('人民币汇率不可用，已回退 USDT 显示。'), { type: 'warning' });
                    return;
                  }
                  setAssetCurrency(value);
                }}
              >
                {value === 'RMB' ? t('人民币（估算）') : value}
              </button>
            ))}
          </div>
        }
        onRefresh={() => void load('manual')}
        refreshing={refreshing}
      />

      <div className="mt-4 space-y-2">
        {accountMode === 'OKX_ERROR' ? <Alert severity="error">{t('OKX账户接口异常：')}{String(state.account?.message ?? '')}</Alert> : null}
        {state.contractsError ? <Alert severity="warning">{t('OKX合约机会池暂不可用：')}{state.contractsError}</Alert> : null}
        {state.ordersError ? <Alert severity="warning">{t('待确认订单暂不可用：')}{state.ordersError}</Alert> : null}
        {state.profitError ? <Alert severity="warning">{t('自动交易收益暂不可用：')}{state.profitError}</Alert> : null}
        {state.fxRateError ? <Alert severity="warning">{t('人民币汇率不可用，已回退 USDT：')}{state.fxRateError}</Alert> : null}
        {assetCurrency === 'RMB' && fxRateAvailable ? (
          <Alert severity="info">
            {t('人民币估算采用')} {String(state.fxRate?.source ?? 'CONFIGURED')} {t('汇率 USD/CNY=')}{formatNumber(state.fxRate?.rate, 4)}
            {state.fxRate?.updatedAt ? `${t('，更新时间 ')}${String(state.fxRate.updatedAt)}` : ''}
          </Alert>
        ) : null}
        {state.profit?.message ? <Alert severity="info">{String(state.profit.message)}</Alert> : null}
      </div>

      <div className="mt-7">
        <div className="grid grid-cols-2 gap-x-6 gap-y-2 sm:grid-cols-3 xl:grid-cols-[1.35fr_1fr_1fr_1fr_1.2fr_0.62fr]">
          <Label>{t('账户总权益')}</Label>
          <Label>{t('可用余额')}</Label>
          <Label>{t('今日已实现')}</Label>
          <Label>{t('未实现盈亏')}</Label>
          <Label>{t('已实现净收益')}</Label>
          <Label>{t('当前持仓')}</Label>
        </div>
        <div className="mt-2 grid grid-cols-2 items-baseline gap-x-6 gap-y-3 sm:grid-cols-3 xl:grid-cols-[1.35fr_1fr_1fr_1fr_1.2fr_0.62fr]">
          <Figure lead>{balanceAvailable ? formatAsset(state.account?.equity) : t('不可用')}</Figure>
          <Figure>{balanceAvailable && availableBalance != null ? formatAsset(availableBalance) : t('不可用')}</Figure>
          <Figure tone={toneOf(state.profit?.todayRealizedPnlUsdt)}>{state.profit?.todayRealizedPnlUsdt == null ? t('不可用') : formatAsset(state.profit.todayRealizedPnlUsdt)}</Figure>
          <Figure tone={toneOf(unrealizedPnl)}>{unrealizedPnl == null ? t('不可用') : formatAsset(unrealizedPnl)}</Figure>
          <Figure tone={toneOf(realizedPnl)}>{realizedPnl == null ? t('不可用') : formatAsset(realizedPnl)}</Figure>
          <Figure>{positionCount ?? t('不可用')}</Figure>
        </div>
      </div>

      <div className="mt-5 flex flex-wrap gap-x-5 gap-y-2 text-[13px] leading-5 text-muted">
        <span>{t('风控')} <b className={risk.passed === true ? 'text-up' : risk.passed === false ? 'text-down' : 'text-muted'}>{risk.passed === false ? t('未通过') : risk.passed === true ? t('通过') : t('未知')}</b></span>
        <span>{t('等级')} <b className="font-medium text-ink">{formatStatus(risk.riskLevel)}</b></span>
        <span>{t('建议杠杆')} <b className="font-medium text-ink">{formatLeverage(risk.adjustedLeverage)}</b></span>
        <span>{t('单笔亏损')} <b className="font-medium text-ink">{formatUSDT(risk.maxLossAmount)}</b></span>
        <span>{t('待确认')} <b className="font-medium text-ink">{pendingCount ?? t('不可用')}</b></span>
        <span>{t('检查时间')} <b className="font-medium tabular-nums text-ink">{formatCheckTime(risk.createdAt)}</b></span>
      </div>
      {risk.rejectReason ? <Alert className="mt-3" severity={risk.passed === false ? 'error' : 'warning'}>{String(risk.rejectReason)}</Alert> : null}

      <div className="mt-6 flex flex-wrap gap-x-6 gap-y-2 text-[13px] text-muted">
        <Mini label={t('已实现毛收益')} value={formatAsset(state.profit?.grossRealizedPnlUsdt)} />
        <Mini label={t('交易手续费')} value={formatAsset(state.profit?.tradingFeesUsdt)} color={pnlColor(state.profit?.tradingFeesUsdt)} />
        <Mini label={t('资金费')} value={formatAsset(state.profit?.fundingUsdt)} color={pnlColor(state.profit?.fundingUsdt)} />
        <Mini label={t('预算收益率')} value={formatPercent(state.profit?.budgetRoiPct)} />
        <Mini label={t('合约机会')} value={state.contractsError ? t('不可用') : String(state.contracts.length)} />
        <Mini label={t('自动交易净 PnL')} value={state.profit?.totalNetPnlUsdt == null ? t('不可用') : formatAsset(state.profit.totalNetPnlUsdt)} color={pnlColor(state.profit?.totalNetPnlUsdt)} />
      </div>

      <section className="mt-8">
        <h2 className="mb-2.5 text-[13px] font-semibold">{t('持仓与PnL')}</h2>
        {state.positionsError ? <Alert severity="error">{t('持仓接口调用失败：')}{state.positionsError}</Alert> : <PositionSnapshotTable positions={state.positions} />}
      </section>

      <section className="mt-8">
        <h2 className="mb-2.5 text-[13px] font-semibold">{t('待处理动作')}</h2>
        {pendingCount == null ? <p className="text-[13px] text-muted">{t('待确认订单不可用。')}</p> : pendingCount === 0 ? <p className="text-[13px] text-muted">{t('当前没有待确认订单。')}</p> : (
          <div className="max-w-[640px]">
            {(state.orders as Record<string, unknown>[]).slice(0, 5).map((order) => (
              <Link key={String(order.id)} to="/pendingOrders" className="grid grid-cols-[minmax(0,180px)_88px_1fr] gap-3 border-t border-line py-2.5 text-[13px] hover:bg-sunken">
                <b className="font-semibold tracking-[-0.015em]">{String(order.instId ?? '-')}</b>
                <span className="text-muted">{formatStatus(order.status)}</span>
                <span className="text-muted tabular-nums">{String(order.leverage ?? '-')}{t('x · 止损')} {formatPrice(order.stopLossPrice)}</span>
              </Link>
            ))}
          </div>
        )}
      </section>

      <section className="mt-8">
        <h2 className="mb-2.5 text-[13px] font-semibold">{t('OKX机会池')}</h2>
        {topContracts.length === 0 ? (
          <p className="text-[13px] text-muted">{state.contractsError ? t('合约机会数据不可用。') : t('暂无合约机会。可以在 OKX合约 页面刷新扫描。')}</p>
        ) : (
          <div className="max-w-[760px]">
            {topContracts.map((contract) => (
              <div key={String(contract.instId)} className="grid grid-cols-[minmax(140px,220px)_120px_1fr] items-baseline gap-3 border-t border-line py-2.5 text-[13px]">
                <b className="font-semibold">{String(contract.instId ?? '-')}</b>
                <span className="tabular-nums">{formatPrice(contract.lastPrice)}</span>
                <span style={{ color: pnlColor(contract.changePercent24h) }}>{formatPercent(contract.changePercent24h)} {t('· 评分')} {formatNumber(contract.score, 0)} · {formatLeverage(contract.suggestedLeverage)}</span>
              </div>
            ))}
          </div>
        )}
      </section>
    </PageShell>
  );
}

function Label({ children }: { children: string }) {
  return <div className="text-[12px] font-medium leading-4 text-muted">{children}</div>;
}

function Figure({ children, lead = false, tone }: { children: string | number; lead?: boolean; tone?: 'up' | 'down' }) {
  return (
    <div className={cn('font-sans tabular-nums tracking-[-0.03em]', lead ? 'text-[30px] font-semibold leading-10 tracking-[-0.04em] sm:text-[36px]' : 'text-[20px] font-medium leading-7 sm:text-[22px]', tone === 'up' && 'text-up', tone === 'down' && 'text-down')}>
      {children}
    </div>
  );
}

function Mini({ label, value, color }: { label: string; value: string; color?: string }) {
  return <p>{label} <b className="font-medium text-ink" style={color ? { color } : undefined}>{value}</b></p>;
}

function toneOf(value: unknown): 'up' | 'down' | undefined {
  if (value == null || value === '') return undefined;
  const number = Number(value);
  if (!Number.isFinite(number) || number === 0) return undefined;
  return number > 0 ? 'up' : 'down';
}

function formatAssetMoney(value: unknown, currency: 'USDT' | 'RMB', fxRate?: Record<string, unknown>) {
  if (value == null || value === '') return '-';
  const number = Number(value);
  if (!Number.isFinite(number)) return '-';
  if (currency === 'RMB') {
    const rate = Number(fxRate?.rate);
    if (Number.isFinite(rate) && rate > 0) return t('约 ¥{amount}', { amount: formatNumber(number * rate, 2) });
  }
  return formatUSDT(number);
}

function formatCheckTime(value: unknown) {
  if (typeof value !== 'string' && typeof value !== 'number') return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleTimeString(getLanguage(), {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}
