import { useEffect, useMemo, useRef, useState } from 'react';
import { useNotify } from 'react-admin';
import { RefreshCw } from 'lucide-react';
import { colors } from '../designTokens';
import { quantApi } from '../api/quantApi';
import { ClosePositionButton, ClosePositionDialog, tradingModeLabel, useClosePosition } from '../components/ClosePositionDialog';
import { PageHeader, PageShell } from '../components/PageShell';
import { PanelState } from '../components/PanelState';
import { TradingStatusStrip } from '../components/TradingStatusStrip';
import { ScrollableTable } from '../components/ScrollableTable';
import { Alert } from '../components/ui/alert';
import { Button } from '../components/ui/button';
import { ConfirmDialog } from '../components/ui/confirm-dialog';
import { Dialog, DialogContent } from '../components/ui/dialog';
import { Input } from '../components/ui/field';
import { TableBody, TableCell, TableHead, TableRow } from '../components/ui/table';
import { openPositions, POLL_INTERVAL_MS, positionDirection } from '../displayData';
import { positionKey } from '../components/PositionSnapshotTable';
import { directionTone, formatAutoTradeReason, formatLeverage, formatNumber, formatPercent, formatPrice, formatSide, formatTrend, formatUSDT, formatVolume, pnlColor } from '../formatters';
import { cn } from '../lib/cn';
import { getLanguage, t, useLanguage } from '../i18n';

type ContractCandidate = Record<string, unknown>;
type PositionRow = Record<string, unknown>;
type CandidateWithPosition = ContractCandidate & { positions: PositionRow[] };
type Candle = { timestamp: string; open: number | string; high: number | string; low: number | string; close: number | string; volume: number | string };
type LoadSection = 'contracts' | 'risk' | 'account' | 'orders' | 'positions' | 'selection';
type PlanRequest = { candidate: ContractCandidate; recommended: boolean };
type SortField = 'default' | 'price' | 'change';
type SortDir = 'asc' | 'desc';
const candlePeriods = [
  { label: '分钟', bar: '15m' },
  { label: '小时', bar: '1H' },
  { label: '日', bar: '1D' },
  { label: '周', bar: '1W' },
  { label: '月', bar: '1M' },
];

export function ContractCandidateList() {
  useLanguage();
  const notify = useNotify();
  const [contracts, setContracts] = useState<ContractCandidate[]>([]);
  const [positions, setPositions] = useState<PositionRow[]>([]);
  const [risk, setRisk] = useState<Record<string, unknown>>();
  const [account, setAccount] = useState<Record<string, unknown>>();
  const [pendingCount, setPendingCount] = useState<number | null>(null);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [planRequest, setPlanRequest] = useState<PlanRequest | null>(null);
  const [planning, setPlanning] = useState(false);
  const [scanOpen, setScanOpen] = useState(false);
  const [scanning, setScanning] = useState(false);
  const planningRef = useRef(false);
  const scanningRef = useRef(false);
  const [chartInstId, setChartInstId] = useState('');
  const [errors, setErrors] = useState<Partial<Record<LoadSection, string>>>({});
  const [lastUpdatedAt, setLastUpdatedAt] = useState<Date | null>(null);
  const [aiSelection, setAiSelection] = useState<Record<string, unknown>>();
  const [sortField, setSortField] = useState<SortField>('default');
  const [sortDir, setSortDir] = useState<SortDir>('asc');
  const inFlight = useRef<Promise<void> | null>(null);
  const closePosition = useClosePosition(refreshAfterMutation);

  function load() {
    if (inFlight.current) return inFlight.current;
    const request = loadSections().finally(() => {
      inFlight.current = null;
      setLoading(false);
    });
    inFlight.current = request;
    return request;
  }

  async function loadSections() {
    const [contractData, riskData, accountData, orderData, positionData, selectionData] = await Promise.allSettled([
      quantApi.contractCandidates(),
      quantApi.riskStatus(),
      quantApi.accountSummary(),
      quantApi.pendingOrders(),
      quantApi.positions(),
      quantApi.aiContractSelection(),
    ]);

    const nextErrors: Partial<Record<LoadSection, string>> = {};
    function apply<T>(section: LoadSection, result: PromiseSettledResult<unknown>, fallback: string, accept: (value: T) => void, array = false) {
      if (result.status === 'rejected') {
        nextErrors[section] = result.reason instanceof Error ? result.reason.message : fallback;
      } else if (array ? Array.isArray(result.value) : result.value && typeof result.value === 'object' && !Array.isArray(result.value)) {
        accept(result.value as T);
      } else {
        nextErrors[section] = fallback;
      }
    }
    apply<ContractCandidate[]>('contracts', contractData, t('合约数据加载失败'), (value) => {
      setContracts(value);
      setLastUpdatedAt(new Date());
    }, true);
    apply<Record<string, unknown>>('risk', riskData, t('风控状态加载失败'), setRisk);
    apply<Record<string, unknown>>('account', accountData, t('账户数据加载失败'), setAccount);
    apply<unknown[]>('orders', orderData, t('待确认订单加载失败'), (value) => setPendingCount(value.length), true);
    apply<PositionRow[]>('positions', positionData, t('持仓加载失败'), setPositions, true);
    apply<Record<string, unknown>>('selection', selectionData, t('AI 选择加载失败'), setAiSelection);
    setErrors(nextErrors);
  }

  async function refresh() {
    setRefreshing(true);
    try {
      await Promise.all([load(), closePosition.refreshMode()]);
    } finally {
      setRefreshing(false);
    }
  }

  async function refreshAfterMutation() {
    // An active poll may have taken its snapshot before the mutation completed.
    if (inFlight.current) await inFlight.current;
    await load();
  }

  function planDisabledReason(candidate?: ContractCandidate) {
    if (errors.contracts) return t('候选数据暂不可用，请刷新后重试。');
    if (!candidate) return t('当前没有可生成交易方案的候选');
    if (candidate.action !== 'AUTO_TRADE_ALLOWED') {
      return formatAutoTradeReason(`candidate_action_not_auto_trade_allowed_${candidate.action || 'UNKNOWN'}`);
    }
    if (candidate.trendDirection !== 'BULLISH' && candidate.trendDirection !== 'BEARISH') {
      return t('候选方向不可用');
    }
    const leverage = Number(candidate.suggestedLeverage);
    if (!Number.isFinite(leverage) || leverage <= 0) {
      return t('候选杠杆不可用');
    }
    return closePosition.tradingMode ? '' : t('交易模式不可用');
  }

  async function createAiPlan() {
    if (!planRequest || planningRef.current) return;
    const { candidate, recommended } = planRequest;
    const current = contracts.find((item) => item.instId === candidate.instId);
    const reason = planDisabledReason(current);
    if (reason || current?.trendDirection !== candidate.trendDirection || current?.suggestedLeverage !== candidate.suggestedLeverage) {
      notify(reason || t('候选方向或杠杆已更新，请重新确认。'), { type: 'warning' });
      setPlanRequest(null);
      return;
    }
    planningRef.current = true;
    setPlanning(true);
    try {
      if (await closePosition.refreshMode() !== closePosition.tradingMode) {
        notify(t('交易模式已变更，请重新确认'), { type: 'warning' });
        return;
      }
      const order = await quantApi.createPendingOrder(String(candidate.instId)) as Record<string, unknown>;
      notify(recommended
        ? `${t('推荐方案已创建：')}${String(order.instId)}${t('，请到待确认订单查看')}`
        : `${t('AI计划已创建：')}${String(order.instId)}${t('，请到待确认订单填写金额')}`, { type: 'success' });
      await refreshAfterMutation();
    } catch (error) {
      notify(error instanceof Error ? formatAutoTradeReason(error.message) : t('AI计划生成失败'), { type: 'error', multiLine: true });
    } finally {
      planningRef.current = false;
      setPlanning(false);
      setPlanRequest(null);
    }
  }

  async function scan() {
    if (scanningRef.current) return;
    scanningRef.current = true;
    setScanning(true);
    setScanOpen(false);
    try {
      const result = await quantApi.scanContracts() as { status?: string; message?: string };
      if (result?.status === 'SKIPPED') {
        notify(t('扫描已跳过：{reason}', { reason: formatAutoTradeReason(result.message) }), { type: 'warning', multiLine: true });
      } else if (result?.status !== 'SUCCESS') {
        throw new Error(result?.message || t('合约扫描失败'));
      }
    } catch (error) {
      notify(t('合约扫描失败：{reason}', {
        reason: error instanceof Error ? formatAutoTradeReason(error.message) : t('未知'),
      }), { type: 'error', multiLine: true });
    } finally {
      await refreshAfterMutation();
      scanningRef.current = false;
      setScanning(false);
    }
  }

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => { void load(); }, POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, []);

  const heldPositions = useMemo(() => openPositions(positions), [positions]);
  const candidateContracts = useMemo<CandidateWithPosition[]>(() => contracts.map((contract) => ({
    ...contract,
    positions: heldPositions.filter((position) => position.instId === contract.instId),
  })), [contracts, heldPositions]);

  const sorted = useMemo(() => {
    if (sortField === 'default') return candidateContracts;
    const dir = sortDir === 'asc' ? 1 : -1;
    return [...candidateContracts].sort((a, b) => {
      if (sortField === 'price') return (Number(a.lastPrice ?? 0) - Number(b.lastPrice ?? 0)) * dir;
      if (sortField === 'change') return (Number(a.changePercent24h ?? 0) - Number(b.changePercent24h ?? 0)) * dir;
      return 0;
    });
  }, [candidateContracts, sortField, sortDir]);

  const filtered = useMemo(() => {
    const text = query.trim().toLowerCase();
    if (!text) return sorted;
    return sorted.filter((item) => [item.instId, item.baseCurrency, item.trendDirection, ...item.positions.map(positionDirection)].some((value) => String(value ?? '').toLowerCase().includes(text)));
  }, [sorted, query]);

  const top = highestScore(contracts);
  const recommendation = contracts.find((candidate) => candidate.action === 'AUTO_TRADE_ALLOWED');
  const recommendationDisabledReason = planDisabledReason(recommendation);
  const totalPnl = heldPositions.reduce((sum, position) => sum + Number(position.unrealizedPnl ?? 0), 0);
  const sortLabel = sortField === 'default' ? t('默认排序') : sortField === 'change' ? `${t('涨幅')}${sortDir === 'asc' ? '↑' : '↓'}` : `${t('价格')}${sortDir === 'asc' ? '↑' : '↓'}`;

  return (
    <PageShell title={t('合约机会')}>
      <PageHeader
        title={t('AI 合约机会')}
        subtitle={`${lastUpdatedAt ? `${t('行情刷新 ')}${lastUpdatedAt.toLocaleTimeString(getLanguage())}` : t('行情准备中')}${refreshing ? t(' · 更新中') : ''}`}
        onRefresh={() => void refresh()}
        actions={(
          <Button variant="ghost" disabled={scanning} aria-busy={scanning} onClick={() => setScanOpen(true)}>
            <RefreshCw size={14} className={scanning ? 'animate-spin' : undefined} />
            {scanning ? t('扫描中') : t('重新扫描')}
          </Button>
        )}
        refreshing={refreshing}
        filters={
          <>
            <Input className="h-8 w-full text-[13px] font-normal sm:w-56" value={query} placeholder={t('搜索合约')} aria-label={t('搜索合约')} onChange={(event) => setQuery(event.target.value)} />
            <Button variant="ghost" onClick={() => {
              if (sortField === 'default') { setSortField('change'); setSortDir('asc'); }
              else if (sortField === 'change') { if (sortDir === 'asc') setSortDir('desc'); else { setSortField('price'); setSortDir('asc'); } }
              else if (sortDir === 'asc') setSortDir('desc');
              else { setSortField('default'); setSortDir('asc'); }
            }}>{sortLabel}</Button>
            <span title={recommendationDisabledReason || undefined}>
              <Button variant="ghost" disabled={planning || Boolean(recommendationDisabledReason)}
                onClick={() => recommendation && setPlanRequest({ candidate: recommendation, recommended: true })}>
                {planning && planRequest?.recommended ? t('生成中') : t('生成推荐方案')}
              </Button>
              {recommendationDisabledReason && <span className="ml-2 text-[12px] text-muted">{recommendationDisabledReason}</span>}
            </span>
          </>
        }
      />
      <div className="mt-4 space-y-4">
        <TradingStatusStrip risk={risk} account={account} pendingCount={pendingCount} />
        <SectionError label={t('风控')} message={errors.risk} />
        <SectionError label={t('账户')} message={errors.account} />
        <SectionError label={t('待确认订单')} message={errors.orders} />
        <div className="grid grid-cols-2 items-baseline gap-6 sm:grid-cols-3">
          <Stat label={t('准入候选')} value={errors.contracts && !lastUpdatedAt ? t('不可用') : String(contracts.length)} hint={`${t('当前持仓 ')}${heldPositions.length}`} />
          <Stat label={t('最高分候选')} value={String(top?.instId ?? '--')} hint={`${formatTrend(top?.trendDirection)} ${formatNumber(top?.score, 0)}${t('分')}`} />
          <Stat label={t('持仓浮盈亏')} value={errors.positions && positions.length === 0 ? t('不可用') : formatUSDT(totalPnl)} color={pnlColor(totalPnl)} hint={t('OKX未实现盈亏')} />
        </div>
        <Alert severity="info">{t('AI 在规则准入池内主导合约、方向和入场模式；规则继续计算价格、仓位并执行所有硬风控。')}</Alert>
        <SectionError label={t('当前持仓')} message={errors.positions} />
        <SectionError label={t('AI 选择')} message={errors.selection} />
        {(!errors.selection || aiSelection) && <Alert severity={aiSelection?.source === 'RULE_FALLBACK' ? 'warning' : 'info'}>
          {aiSelection?.source === 'AI_PRIMARY'
            ? `${t('AI 主选 ')}${String(aiSelection.selectedInstId)} ${formatSide(aiSelection.direction)} ${String(aiSelection.entryMode)}${t(' · 置信度 ')}${formatPercent(Number(aiSelection.confidence ?? 0) * 100)}`
            : aiSelection?.source === 'AI_WAIT' ? t('AI 本轮选择等待，不会触发规则保底下单。')
              : aiSelection?.source === 'RULE_FALLBACK' ? `${t('AI 不可用，已进入规则保底：')}${String(aiSelection.fallbackReason ?? t('原因不可用'))}`
                : t('AI 尚未完成本轮选择。')}
        </Alert>}
        <SectionError label={t('合约机会')} message={errors.contracts} />
        {loading ? <PanelState loading /> : filtered.length === 0 ? (
          errors.contracts ? null : <PanelState>{t('当前没有满足数据准入条件的合约候选。')}</PanelState>
        ) : (
          <ScrollableTable label={t('合约机会')}>
            <TableHead>
              <TableRow>
                <TableCell>#</TableCell>
                <TableCell>{t('合约')}</TableCell>
                <TableCell>{t('方向')}</TableCell>
                <TableCell align="right">{t('最新价格')}</TableCell>
                <TableCell align="right">24h</TableCell>
                <TableCell align="right">{t('综合评分')}</TableCell>
                <TableCell align="right">{t('建议杠杆')}</TableCell>
                <TableCell align="right">{t('24h成交量')}</TableCell>
                <TableCell align="right">{t('未实现盈亏')}</TableCell>
                <TableCell align="right">{t('操作')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {filtered.map((contract, index) => {
                const planReason = planDisabledReason(contract);
                const change = Number(contract.changePercent24h);
                const held = contract.positions.length > 0;
                const direction = contract.positions.length === 1 ? positionDirection(contract.positions[0]) : contract.trendDirection;
                const tone = directionTone(direction);
                const positionPnl = contract.positions.reduce((sum, position) => sum + Number(position.unrealizedPnl ?? 0), 0);
                return (
                  <TableRow key={String(contract.instId)}>
                    <TableCell>{index + 1}</TableCell>
                    <TableCell>
                      <button type="button" className="font-semibold tracking-[-0.015em]" onClick={() => setChartInstId(String(contract.instId))}>{String(contract.instId ?? '--')}</button>
                    </TableCell>
                    <TableCell className={cn(tone === 'success' && 'text-up', tone === 'error' && 'text-down')}>{contract.positions.length === 1 ? formatSide(direction) : formatTrend(contract.trendDirection)}</TableCell>
                    <TableCell align="right">{formatPrice(contract.lastPrice)}</TableCell>
                    <TableCell align="right" style={{ color: pnlColor(change) }}>{formatPercent(change)}</TableCell>
                    <TableCell align="right">{formatNumber(contract.score, 0)}</TableCell>
                    <TableCell align="right">{formatLeverage(contract.suggestedLeverage)}</TableCell>
                    <TableCell align="right">{formatVolume(contract.volume24h)}</TableCell>
                    <TableCell align="right" style={{ color: held ? pnlColor(positionPnl) : undefined }}>{held ? formatUSDT(positionPnl) : t('未持仓')}</TableCell>
                    <TableCell align="right">
                      <div className="flex flex-wrap justify-end gap-2">
                        {contract.positions.map((position) => (
                          <ClosePositionButton key={positionKey(position)} disabledReason={closePosition.disabledReason}
                            loading={closePosition.loading} onClick={() => closePosition.select(position)}
                            label={`${t('平仓')} · ${formatSide(positionDirection(position))} · ${formatSide(position.marginMode)}`} />
                        ))}
                        <Button variant="primary" disabled={planning || Boolean(planReason)}
                          onClick={() => setPlanRequest({ candidate: contract, recommended: false })}>
                          {planning && !planRequest?.recommended && planRequest?.candidate.instId === contract.instId ? t('推理中') : t('AI计划')}
                        </Button>
                      </div>
                      {planReason && <div className="mt-1 text-[12px] text-muted">{planReason}</div>}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </ScrollableTable>
        )}
      </div>
      <CandleDialog instId={chartInstId} open={Boolean(chartInstId)} onClose={() => setChartInstId('')} />
      <ClosePositionDialog {...closePosition.dialog} />
      {planRequest && <ConfirmDialog
        open
        title={t('确认生成待确认订单')}
        tone={closePosition.tradingMode === 'LIVE' ? 'warning' : 'primary'}
        content={(
          <div className="space-y-1">
            <p>{t('合约')} · {String(planRequest.candidate.instId)}</p>
            <p>{t('候选方向')} · {formatSide(planRequest.candidate.trendDirection === 'BULLISH' ? 'long' : 'short')}</p>
            <p>{t('建议杠杆')} · {formatLeverage(planRequest.candidate.suggestedLeverage)}</p>
            <p>{t('交易模式')} · {tradingModeLabel(closePosition.tradingMode)}</p>
            <p>{t('实际方向和杠杆以生成后的待确认订单为准。')}</p>
          </div>
        )}
        confirmLabel={planning ? t('生成中') : t('确认')}
        cancelLabel={t('返回')}
        pending={planning}
        onConfirm={() => void createAiPlan()}
        onClose={() => setPlanRequest(null)}
      />}
      <ConfirmDialog
        open={scanOpen}
        title={t('重新扫描')}
        content={t('扫描会触发自动交易评估。启用自动交易且满足下单条件时，后端可能按当前交易模式提交订单。')}
        confirmLabel={t('重新扫描')}
        cancelLabel={t('返回')}
        tone="warning"
        onConfirm={() => void scan()}
        onClose={() => setScanOpen(false)}
      />
    </PageShell>
  );
}

function highestScore(candidates: ContractCandidate[]) {
  return candidates.reduce<ContractCandidate | undefined>((best, candidate) => {
    const score = Number(candidate.score ?? NaN);
    if (!Number.isFinite(score)) return best;
    return !best || score > Number(best.score) ? candidate : best;
  }, undefined);
}

function SectionError({ label, message }: { label: string; message?: string }) {
  return message ? <Alert severity="error">{label} · {message}</Alert> : null;
}

function Stat({ label, value, hint, color }: { label: string; value: string; hint?: string; color?: string }) {
  return (
    <div>
      <div className="text-[12px] font-medium text-muted">{label}</div>
      <div className="mt-1 break-all text-[22px] font-medium leading-7 tracking-[-0.03em] tabular-nums" style={color ? { color } : undefined}>{value}</div>
      {hint ? <div className="mt-1 text-[12px] text-faint">{hint}</div> : null}
    </div>
  );
}

function CandleDialog({ instId, open, onClose }: { instId: string; open: boolean; onClose: () => void }) {
  useLanguage();
  const notify = useNotify();
  const [bar, setBar] = useState('15m');
  const [candles, setCandles] = useState<Candle[]>([]);
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    if (!open || !instId) return;
    let active = true;
    let firstLoad = true;
    let requestRunning = false;
    async function loadCandles() {
      if (requestRunning) return;
      requestRunning = true;
      if (firstLoad) setLoading(true);
      try {
        const data = await quantApi.contractCandles(instId, bar);
        if (active) setCandles(Array.isArray(data) ? data as Candle[] : []);
      } catch (error) {
        if (active) {
          notify(error instanceof Error ? error.message : t('K线加载失败'), { type: 'error' });
          setCandles([]);
        }
      } finally {
        requestRunning = false;
        if (active) { setLoading(false); firstLoad = false; }
      }
    }
    void loadCandles();
    const timer = window.setInterval(() => void loadCandles(), 5000);
    return () => { active = false; window.clearInterval(timer); };
  }, [bar, instId, notify, open]);
  const positive = candles.length >= 2 && Number(candles[candles.length - 1].close) >= Number(candles[0].open);
  return (
    <Dialog open={open} onOpenChange={(next) => { if (!next) onClose(); }}>
      <DialogContent title={`${instId} ${t('K线图')}`} description={positive ? t('多头结构更明显') : t('空头结构更明显')} className="w-[min(960px,calc(100vw-32px))]">
        <div className="mb-3 flex flex-wrap gap-2" aria-label={t('K线周期')}>
          {candlePeriods.map((period) => (
            <button key={period.bar} type="button" className={cn('h-8 rounded-lg px-3 text-[13px]', bar === period.bar ? 'bg-accent-soft text-accent-ink' : 'text-muted')} onClick={() => setBar(period.bar)}>{t(period.label)}</button>
          ))}
        </div>
        {loading ? <PanelState loading /> : <CandleChart candles={candles} bar={bar} />}
      </DialogContent>
    </Dialog>
  );
}


function CandleChart({ candles, bar }: { candles: Candle[]; bar: string }) {
  useLanguage();
  const chartRef = useRef<HTMLDivElement>(null);
  const [chartWidth, setChartWidth] = useState(0);
  useEffect(() => {
    const chart = chartRef.current;
    if (!chart) return;
    const observer = new ResizeObserver(([entry]) => setChartWidth(Math.floor(entry.contentRect.width)));
    observer.observe(chart);
    return () => observer.disconnect();
  }, []);

  const width = Math.max(chartWidth, 240);
  const height = width < 600 ? 288 : 416;
  const padding = { top: 12, right: 88, left: 12 };
  const priceHeight = height - 112;
  const volumeTop = priceHeight + 36;
  const volumeHeight = 48;
  const data = candles
    .map((item) => ({
      timestamp: item.timestamp,
      time: new Date(item.timestamp),
      open: Number(item.open),
      high: Number(item.high),
      low: Number(item.low),
      close: Number(item.close),
      volume: Number(item.volume),
    }))
    .filter((item) => [item.open, item.high, item.low, item.close, item.volume].every(Number.isFinite));

  if (data.length === 0) {
    return (
      <PanelState>{t("暂无K线数据")}</PanelState>
    );
  }

  const min = Math.min(...data.map((item) => item.low));
  const max = Math.max(...data.map((item) => item.high));
  const range = max - min || 1;
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = priceHeight - padding.top;
  const step = plotWidth / data.length;
  const candleWidth = Math.max(0.75, Math.min(8, step * 0.62));
  const y = (price: number) => padding.top + ((max - price) / range) * plotHeight;
  const maxVolume = Math.max(...data.map((item) => item.volume), 1);
  const volumeY = (volume: number) => volumeTop + volumeHeight - (volume / maxVolume) * volumeHeight;
  const first = data[0];
  const last = data[data.length - 1];
  const trendPositive = last.close >= first.open;
  const changePercent = first.open ? ((last.close - first.open) / first.open) * 100 : 0;
  const grid = Array.from({ length: 5 }, (_, index) => min + (range * index) / 4);
  const timeTicks = timeTickIndexes(data.length, width < 400 ? 2 : width < 650 ? 4 : 6).map((index) => ({ index, item: data[index] }));
  const trendLine = regressionTrendLine(data, padding.left, step, y);

  return (
    <div ref={chartRef} className="overflow-hidden">
      <div className="flex flex-wrap items-baseline gap-2 border-b border-line px-3 py-2 text-[13px]">
        <span className="text-muted">{t("实时最新价")}</span>
        <span className="font-semibold tabular-nums">{formatPrice(last.close)}</span>
        <span className={trendPositive ? 'font-medium text-up' : 'font-medium text-down'}>{formatPercent(changePercent)}</span>
      </div>
      <svg viewBox={`0 0 ${width} ${height}`} width="100%" height={height} role="img" aria-label={t("价格K线及成交量")} style={{ display: 'block' }}>
        {grid.map((price) => {
          const lineY = y(price);
          return (
            <g key={price}>
              <line x1={padding.left} y1={lineY} x2={width - padding.right} y2={lineY} stroke={colors.divider} strokeWidth="1" />
              <text x={width - 8} y={lineY + 4} textAnchor="end" fill={colors.muted} fontSize="12">
                {formatPrice(price)}
              </text>
            </g>
          );
        })}
        <line x1={padding.left} y1={priceHeight} x2={width - padding.right} y2={priceHeight} stroke={colors.divider} strokeWidth="1" />
        <line x1={padding.left} y1={volumeTop - 10} x2={width - padding.right} y2={volumeTop - 10} stroke={colors.divider} strokeWidth="1" />
        <text x={padding.left} y={volumeTop - 18} fill={colors.muted} fontSize="12">
          {t("分时量")}
        </text>
        <path
          d={data.map((item, index) => `${index === 0 ? 'M' : 'L'} ${padding.left + index * step + step / 2} ${y(item.close)}`).join(' ')}
          fill="none"
          stroke={trendPositive ? colors.success : colors.error}
          strokeWidth="2"
          opacity="0.42"
        />
        {trendLine && (
          <line
            x1={trendLine.x1}
            y1={trendLine.y1}
            x2={trendLine.x2}
            y2={trendLine.y2}
            stroke={trendPositive ? colors.primary : colors.warning}
            strokeWidth="2.4"
            strokeDasharray="8 6"
            opacity="0.92"
          />
        )}
        {data.map((item, index) => {
          const x = padding.left + index * step + step / 2;
          const up = item.close >= item.open;
          const color = up ? colors.success : colors.error;
          const bodyTop = y(Math.max(item.open, item.close));
          const bodyBottom = y(Math.min(item.open, item.close));
          const bodyHeight = Math.max(2, bodyBottom - bodyTop);
          return (
            <g key={`${item.timestamp}-${index}`}>
              <line x1={x} y1={y(item.high)} x2={x} y2={y(item.low)} stroke={color} strokeWidth="1.4" />
              <rect
                x={x - candleWidth / 2}
                y={bodyTop}
                width={candleWidth}
                height={bodyHeight}
                fill={color}
              />
              <rect
                x={x - candleWidth / 2}
                y={volumeY(item.volume)}
                width={candleWidth}
                height={Math.max(1, volumeTop + volumeHeight - volumeY(item.volume))}
                fill={color}
                opacity="0.42"
              />
            </g>
          );
        })}
        {timeTicks.map(({ index, item }) => {
          const x = padding.left + index * step + step / 2;
          return (
            <g key={`time-${item.timestamp}`}>
              <line x1={x} y1={padding.top} x2={x} y2={volumeTop + volumeHeight} stroke={colors.divider} opacity="0.6" strokeWidth="1" />
              <text x={Math.max(48, x)} y={height - 8} fill={colors.muted} fontSize="12" textAnchor="middle">
                {formatCandleTime(item.time, bar)}
              </text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}


function regressionTrendLine(
  data: Array<{ close: number }>,
  paddingLeft: number,
  step: number,
  y: (price: number) => number,
) {
  if (data.length < 2) {
    return null;
  }
  const n = data.length;
  const sumX = data.reduce((sum, _, index) => sum + index, 0);
  const sumY = data.reduce((sum, item) => sum + item.close, 0);
  const sumXY = data.reduce((sum, item, index) => sum + index * item.close, 0);
  const sumXX = data.reduce((sum, _, index) => sum + index * index, 0);
  const denominator = n * sumXX - sumX * sumX;
  if (denominator === 0) {
    return null;
  }
  const slope = (n * sumXY - sumX * sumY) / denominator;
  const intercept = (sumY - slope * sumX) / n;
  const startPrice = intercept;
  const endPrice = intercept + slope * (n - 1);
  return {
    x1: paddingLeft + step / 2,
    y1: y(startPrice),
    x2: paddingLeft + (n - 1) * step + step / 2,
    y2: y(endPrice),
  };
}

function timeTickIndexes(length: number, maxTicks: number) {
  if (length <= 1) {
    return [0];
  }
  const tickCount = Math.min(maxTicks, length);
  const indexes = new Set<number>();
  for (let i = 0; i < tickCount; i += 1) {
    indexes.add(Math.round((i * (length - 1)) / (tickCount - 1)));
  }
  return [...indexes].sort((a, b) => a - b);
}

function formatCandleTime(date: Date, bar: string) {
  const pad = (value: number) => String(value).padStart(2, '0');
  const year = date.getFullYear();
  const month = pad(date.getMonth() + 1);
  const day = pad(date.getDate());
  const hour = pad(date.getHours());
  const minute = pad(date.getMinutes());
  if (bar.endsWith('m')) {
    return `${month}-${day} ${hour}:${minute}`;
  }
  if (bar.endsWith('H')) {
    return `${month}-${day} ${hour}:00`;
  }
  if (bar === '1M') {
    return `${year}-${month}`;
  }
  return `${year}-${month}-${day}`;
}

