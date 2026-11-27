import { useEffect, useMemo, useRef, useState } from 'react';
import { useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { PageHeader, PageShell } from '../components/PageShell';
import { PanelState } from '../components/PanelState';
import { PendingOrderReviewDialog } from '../components/PendingOrderReviewDialog';
import { ScrollableTable } from '../components/ScrollableTable';
import { StatusChip } from '../components/StatusChip';
import { TradingStatusStrip } from '../components/TradingStatusStrip';
import { Alert } from '../components/ui/alert';
import { Button } from '../components/ui/button';
import { ConfirmDialog } from '../components/ui/confirm-dialog';
import { Input } from '../components/ui/field';
import { TableBody, TableCell, TableHead, TableRow } from '../components/ui/table';
import { directionTone, formatAction, formatAutoTradeReason, formatLeverage, formatPrice, formatSide } from '../formatters';
import { cn } from '../lib/cn';
import { t, useLanguage } from '../i18n';

type PendingOrderState = {
  risk?: Record<string, unknown>;
  account?: Record<string, unknown>;
  orders: Record<string, unknown>[];
  tradingMode: string;
};

const emptyState: PendingOrderState = { orders: [], tradingMode: 'UNKNOWN' };
type LoadErrors = Partial<Record<'risk' | 'account' | 'orders' | 'runtime', string>>;

function PendingOrderActions({ record, onChanged, tradingMode, stale }: {
  record: Record<string, unknown>;
  onChanged: () => void;
  tradingMode: string;
  stale: boolean;
}) {
  useLanguage();
  const notify = useNotify();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const id = String(record.id);
  const canConfirm = !stale && (tradingMode === 'LIVE' || tradingMode === 'DEMO');

  useEffect(() => {
    if (!canConfirm) setConfirmOpen(false);
    if (stale) setCancelOpen(false);
  }, [canConfirm, stale]);

  async function confirmOrder(marginAmount: string) {
    if (!canConfirm || submitting) return;
    setSubmitting(true);
    try {
      const result = (await quantApi.confirmOrder(id, marginAmount)) as Record<string, unknown>;
      if (result.executed !== true) throw new Error(String(result.message ?? t('订单未提交OKX')));
      notify(String(result.message ?? t('OKX委托已提交，等待成交，请到OKX当前委托/持仓确认')), { type: 'info' });
      onChanged();
    } catch (error) {
      notify(error instanceof Error ? formatAutoTradeReason(error.message) : t('确认订单失败'), { type: 'error' });
    } finally {
      setSubmitting(false);
      setConfirmOpen(false);
    }
  }

  async function cancelOrder() {
    if (stale || submitting) return;
    setSubmitting(true);
    try {
      await quantApi.cancelOrder(id);
      notify(t('订单已取消'), { type: 'success' });
      onChanged();
    } catch (error) {
      notify(error instanceof Error ? error.message : t('取消订单失败'), { type: 'error' });
    } finally {
      setSubmitting(false);
      setCancelOpen(false);
    }
  }

  return (
    <div className="flex justify-end gap-2">
      <Button variant={tradingMode === 'LIVE' ? 'danger' : 'primary'} disabled={submitting || !canConfirm} onClick={() => setConfirmOpen(true)}>
        {tradingMode === 'LIVE' ? t('LIVE 确认') : tradingMode === 'DEMO' ? t('DEMO 确认') : tradingMode === 'DISABLED' ? t('交易已关闭') : t('交易模式不可用')}
      </Button>
      <Button variant="ghost" disabled={stale || submitting} onClick={() => setCancelOpen(true)}>{t('取消')}</Button>
      <PendingOrderReviewDialog open={confirmOpen && canConfirm} record={record} submitting={submitting} tradingMode={tradingMode} onConfirm={(marginAmount) => void confirmOrder(marginAmount)} onClose={() => setConfirmOpen(false)} />
      <ConfirmDialog open={cancelOpen && !stale} title={t('取消待确认订单')} content={t('取消 {instrument} 的待确认订单。', { instrument: String(record.instId) })} confirmLabel={t('取消订单')} cancelLabel={t('返回')} pending={submitting} onConfirm={() => void cancelOrder()} onClose={() => setCancelOpen(false)} />
    </div>
  );
}

export function PendingOrderList() {
  useLanguage();
  const [state, setState] = useState<PendingOrderState>(emptyState);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [query, setQuery] = useState('');
  const [errors, setErrors] = useState<LoadErrors>({});
  const [runtimeLoading, setRuntimeLoading] = useState(true);
  const [ordersLoaded, setOrdersLoaded] = useState(false);
  const loadVersion = useRef(0);

  async function load(refresh = false) {
    const version = ++loadVersion.current;
    if (refresh) setRefreshing(true);
    setRuntimeLoading(true);

    async function read(section: keyof LoadErrors, request: Promise<unknown>, fallback: string, apply: (value: unknown) => void) {
      try {
        const result = await request;
        if (version !== loadVersion.current) return;
        apply(result);
        setErrors((current) => ({ ...current, [section]: undefined }));
      } catch (error) {
        if (version === loadVersion.current) {
          setErrors((current) => ({ ...current, [section]: error instanceof Error ? error.message : fallback }));
        }
      }
    }
    const risk = read('risk', quantApi.riskStatus(), t('风控状态加载失败'), (value) => {
      if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(t('风控状态加载失败'));
      setState((current) => ({ ...current, risk: value as Record<string, unknown> }));
    });
    const account = read('account', quantApi.accountSummary(), t('账户数据加载失败'), (value) => {
      if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(t('账户数据加载失败'));
      setState((current) => ({ ...current, account: value as Record<string, unknown> }));
    });
    const orders = read('orders', quantApi.pendingOrders(), t('待确认订单加载失败'), (value) => {
      if (!Array.isArray(value)) throw new Error(t('待确认订单响应格式异常'));
      setState((current) => ({ ...current, orders: value as Record<string, unknown>[] }));
      setOrdersLoaded(true);
    }).finally(() => {
      if (version === loadVersion.current) setLoading(false);
    });
    const runtime = read('runtime', quantApi.runtimeStatus(), t('运行状态加载失败'), (value) => {
      const mode = (value as { tradingMode?: unknown } | null)?.tradingMode;
      if (mode !== 'LIVE' && mode !== 'DEMO' && mode !== 'DISABLED') throw new Error(t('交易模式不可用'));
      setState((current) => ({ ...current, tradingMode: mode }));
    }).finally(() => {
      if (version === loadVersion.current) setRuntimeLoading(false);
    });
    await Promise.allSettled([risk, account, orders, runtime]);
    if (version === loadVersion.current) setRefreshing(false);
  }

  useEffect(() => {
    void load();
    return () => { loadVersion.current++; };
  }, []);

  const tradingMode = errors.runtime || runtimeLoading ? 'UNKNOWN' : state.tradingMode;

  const filtered = useMemo(() => {
    const text = query.trim().toLowerCase();
    const orders = [...state.orders].sort((left, right) => String(right.createdAt ?? '').localeCompare(String(left.createdAt ?? '')));
    if (!text) return orders;
    return orders.filter((order) => [order.instId, order.action, order.side, order.posSide, order.status].some((value) => String(value ?? '').toLowerCase().includes(text)));
  }, [query, state.orders]);

  return (
    <PageShell title={t('待确认订单')}>
      <PageHeader
        title={t('待确认订单')}
        subtitle={t('这里是 OKX 订单提交前的人工审核台。确认前必须填写保证金并复核方向、杠杆、止损和止盈。')}
        onRefresh={() => void load(true)}
        refreshing={refreshing}
        filters={<Input className="h-8 w-full text-[13px] font-normal sm:w-60" value={query} placeholder={t('搜索合约、方向、状态')} aria-label={t('搜索合约、方向、状态')} onChange={(event) => setQuery(event.target.value)} />}
      />
      <div className="mt-4 space-y-3">
        <TradingStatusStrip risk={state.risk} account={state.account} pendingCount={ordersLoaded ? state.orders.length : null} />
        {errors.risk && <Alert severity="error">{t('风控')} · {errors.risk}</Alert>}
        {errors.account && <Alert severity="error">{t('账户')} · {errors.account}</Alert>}
        {errors.runtime && <Alert severity="error">{t('运行状态')} · {errors.runtime}</Alert>}
        <Alert severity={tradingMode === 'LIVE' ? 'error' : tradingMode === 'DEMO' ? 'info' : 'warning'}>
          {tradingMode === 'UNKNOWN'
            ? runtimeLoading ? t('正在读取交易模式') : t('交易模式不可用')
            : <>{t('当前模式：')}{tradingMode} · {tradingMode === 'LIVE' ? t('确认会操作真实资金。') : tradingMode === 'DEMO' ? t('确认只提交 OKX 模拟交易。') : t('交易写请求已关闭。')}</>}
        </Alert>
        {errors.orders && <Alert severity="error">{t('待确认订单')} · {errors.orders}</Alert>}
        {loading ? <PanelState loading /> : filtered.length === 0 ? (
          errors.orders ? null : <PanelState>{t('当前没有匹配的待确认订单。')}</PanelState>
        ) : (
          <ScrollableTable label={t('待确认订单')}>
            <TableHead>
              <TableRow>
                <TableCell>{t('合约')}</TableCell>
                <TableCell>{t('动作')}</TableCell>
                <TableCell>{t('方向')}</TableCell>
                <TableCell align="right">{t('价格')}</TableCell>
                <TableCell align="right">{t('杠杆')}</TableCell>
                <TableCell align="right">{t('止损')}</TableCell>
                <TableCell align="right">{t('止盈')}</TableCell>
                <TableCell>{t('状态')}</TableCell>
                <TableCell align="right">{t('操作')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {filtered.map((order) => (
                <TableRow key={String(order.id)}>
                  <TableCell className="font-semibold">{String(order.instId ?? '-')}</TableCell>
                  <TableCell>{formatAction(order.action)}</TableCell>
                  <TableCell><span className={cn('font-medium', directionTone(order.side) === 'success' && 'text-up', directionTone(order.side) === 'error' && 'text-down')}>{formatSide(order.side)} / {formatSide(order.posSide)}</span></TableCell>
                  <TableCell align="right">{formatPrice(order.price)}</TableCell>
                  <TableCell align="right">{formatLeverage(order.leverage)}</TableCell>
                  <TableCell align="right">{formatPrice(order.stopLossPrice)}</TableCell>
                  <TableCell align="right">{formatPrice(order.takeProfitPrice)}</TableCell>
                  <TableCell><StatusChip value={String(order.status ?? '-')} /></TableCell>
                  <TableCell align="right"><PendingOrderActions record={order} tradingMode={tradingMode} stale={Boolean(errors.orders)} onChanged={() => void load(true)} /></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </ScrollableTable>
        )}
        {ordersLoaded && <p className="text-[12px] text-muted">{t('显示 {shown} / {total} 条待确认订单。', { shown: filtered.length, total: state.orders.length })}</p>}
      </div>
    </PageShell>
  );
}
