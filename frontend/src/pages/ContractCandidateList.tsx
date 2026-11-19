import AutoAwesomeIcon from '@mui/icons-material/AutoAwesome';
import CloseIcon from '@mui/icons-material/Close';
import OutputIcon from '@mui/icons-material/Output';
import RefreshIcon from '@mui/icons-material/Refresh';
import SearchIcon from '@mui/icons-material/Search';
import ShowChartIcon from '@mui/icons-material/ShowChart';
import SortIcon from '@mui/icons-material/Sort';
import TrendingDownIcon from '@mui/icons-material/TrendingDown';
import TrendingUpIcon from '@mui/icons-material/TrendingUp';
import {
  Alert,
  Box,
  Button,
  ButtonBase,
  Chip,
  CircularProgress,
  Dialog,
  DialogContent,
  DialogTitle,
  IconButton,
  InputAdornment,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNotify } from 'react-admin';
import { colors } from '../designTokens';
import { PanelState } from '../components/PanelState';
import { quantApi } from '../api/quantApi';
import { ClosePositionDialog } from '../components/ClosePositionDialog';
import { insetPanel, panel, interactivePanel } from '../components/surfaces';
import { PageHeader, PageShell } from '../components/PageShell';
import { positionKey } from '../components/PositionSnapshotTable';
import { TradingStatusStrip } from '../components/TradingStatusStrip';
import { directionTone, formatLeverage, formatNumber, formatPercent, formatPrice, formatSide, formatTrend, formatUSDT, formatVolume, pnlColor } from '../formatters';

type ContractCandidate = Record<string, unknown>;
type PositionRow = Record<string, unknown>;
type CandidateWithPosition = ContractCandidate & {
  position?: PositionRow;
};
type Candle = {
  timestamp: string;
  open: number | string;
  high: number | string;
  low: number | string;
  close: number | string;
  volume: number | string;
};

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
  const notify = useNotify();
  const [contracts, setContracts] = useState<ContractCandidate[]>([]);
  const [positions, setPositions] = useState<PositionRow[]>([]);
  const [risk, setRisk] = useState<Record<string, unknown>>();
  const [account, setAccount] = useState<Record<string, unknown>>();
  const [pendingCount, setPendingCount] = useState(0);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [planningInstId, setPlanningInstId] = useState('');
  const [batchPlanning, setBatchPlanning] = useState(false);
  const [chartInstId, setChartInstId] = useState('');
  const [closeDialogPosition, setCloseDialogPosition] = useState<PositionRow | null>(null);
  const [closingKey, setClosingKey] = useState('');
  const [error, setError] = useState('');
  const [lastUpdatedAt, setLastUpdatedAt] = useState<Date | null>(null);
  const [aiSelection, setAiSelection] = useState<Record<string, unknown>>();
  const [sortField, setSortField] = useState<SortField>('default');
  const [sortDir, setSortDir] = useState<SortDir>('asc');
  const loadRunning = useRef(false);

  async function load(silent = false) {
    if (loadRunning.current) return;
    loadRunning.current = true;
    if (silent) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    try {
      const [contractData, riskData, accountData, orderData, positionData, selectionData] = await Promise.all([
        quantApi.contractCandidates(),
        quantApi.riskStatus(),
        quantApi.accountSummary(),
        quantApi.pendingOrders(),
        quantApi.positions(),
        quantApi.aiContractSelection(),
      ]);
      setContracts(Array.isArray(contractData) ? (contractData as ContractCandidate[]) : []);
      setPositions(Array.isArray(positionData) ? (positionData as PositionRow[]) : []);
      setRisk(riskData as Record<string, unknown>);
      setAccount(accountData as Record<string, unknown>);
      setPendingCount(Array.isArray(orderData) ? orderData.length : 0);
      setAiSelection(selectionData as Record<string, unknown>);
      setLastUpdatedAt(new Date());
      setError('');
    } catch (loadError) {
      const message = loadError instanceof Error ? loadError.message : '合约数据加载失败';
      setError(message);
      if (!silent) {
        notify(message, { type: 'error' });
      }
    } finally {
      loadRunning.current = false;
      setLoading(false);
      setRefreshing(false);
    }
  }

  async function createAiPlan(instId: string) {
    setPlanningInstId(instId);
    try {
      const order = (await quantApi.createPendingOrder(instId)) as Record<string, unknown>;
      notify(`AI计划已创建：${String(order.instId)}，请到待确认订单填写金额`, { type: 'success' });
      await load(true);
    } catch (planError) {
      notify(planError instanceof Error ? planError.message : 'AI计划生成失败', { type: 'error', multiLine: true });
    } finally {
      setPlanningInstId('');
    }
  }

  async function confirmClosePosition() {
    if (!closeDialogPosition) {
      return;
    }
    const key = positionKey(closeDialogPosition);
    setClosingKey(key);
    try {
      await quantApi.closePosition({
        instId: String(closeDialogPosition.instId ?? ''),
        posSide: String(closeDialogPosition.posSide ?? ''),
        marginMode: String(closeDialogPosition.marginMode ?? ''),
      });
      notify(`已提交平仓：${String(closeDialogPosition.instId ?? '-')}`, { type: 'success' });
      setCloseDialogPosition(null);
      await load(true);
    } catch (closeError) {
      notify(closeError instanceof Error ? closeError.message : '平仓提交失败', { type: 'error', multiLine: true });
    } finally {
      setClosingKey('');
    }
  }

  async function createTopAiPlan() {
    if (contracts.length === 0) {
      notify('当前没有合约可生成计划', { type: 'warning' });
      return;
    }
    setBatchPlanning(true);
    try {
      const selectedInstId = aiSelection?.source === 'AI_PRIMARY' ? String(aiSelection.selectedInstId ?? '') : '';
      const topContract = contracts.find((item) => item.instId === selectedInstId)
        ?? [...contracts].sort((a, b) => Number(b.finalRankScore ?? b.score ?? 0) - Number(a.finalRankScore ?? a.score ?? 0))[0];
      const order = (await quantApi.createPendingOrder(String(topContract.instId))) as Record<string, unknown>;
      notify(`最高分计划已创建：${String(order.instId)}，请到待确认订单查看`, { type: 'success' });
      await load(true);
    } catch (planError) {
      notify(planError instanceof Error ? planError.message : '最高分计划生成失败', { type: 'error', multiLine: true });
    } finally {
      setBatchPlanning(false);
    }
  }

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => void load(true), 5000);
    return () => window.clearInterval(timer);
  }, []);

  const heldContracts = useMemo<CandidateWithPosition[]>(() => {
    return positions
      .filter((position) => Number(position.size ?? 0) !== 0)
      .map((position) => {
        const candidate = contracts.find((contract) => String(contract.instId) === String(position.instId)) ?? {};
        return { ...candidate, instId: position.instId, position };
      });
  }, [contracts, positions]);

  const candidateContracts = useMemo<CandidateWithPosition[]>(() => contracts.map((contract) => ({
    ...contract,
    position: positions.find((position) => String(position.instId) === String(contract.instId)),
  })), [contracts, positions]);

  const sorted = useMemo(() => {
    if (sortField === 'default') return candidateContracts;
    const dir = sortDir === 'asc' ? 1 : -1;
    return [...candidateContracts].sort((a, b) => {
      if (sortField === 'price') {
        const av = Number(a.lastPrice ?? 0);
        const bv = Number(b.lastPrice ?? 0);
        return (av - bv) * dir;
      }
      if (sortField === 'change') {
        const av = Number(a.changePercent24h ?? 0);
        const bv = Number(b.changePercent24h ?? 0);
        return (av - bv) * dir;
      }
      return 0;
    });
  }, [candidateContracts, sortField, sortDir]);

  const filtered = useMemo(() => {
    const text = query.trim().toLowerCase();
    if (!text) {
      return sorted;
    }
    return sorted.filter((item) =>
      [item.instId, item.baseCurrency, item.trendDirection, item.position?.posSide]
        .some((value) => String(value ?? '').toLowerCase().includes(text)),
    );
  }, [sorted, query]);

  const top = contracts[0] as ContractCandidate | undefined;
  const totalPnl = heldContracts.reduce((sum, contract) => sum + Number(contract.position?.unrealizedPnl ?? 0), 0);

  return (
    <PageShell title="合约机会">
      <Stack spacing={2}>
        <PageHeader
          title="AI 合约机会"
          subtitle={`${lastUpdatedAt ? `行情刷新 ${lastUpdatedAt.toLocaleTimeString()}` : '行情准备中'}${refreshing ? ' · 更新中' : ''}`}
          filters={
            <>
            <TextField
              size="small"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索合约"
                inputProps={{ 'aria-label': '搜索合约' }}
              InputProps={{
                startAdornment: (
                  <InputAdornment position="start">
                    <SearchIcon fontSize="small" />
                  </InputAdornment>
                ),
              }}
              sx={{ width: { xs: 1, sm: 220 } }}
            />
            <Button
              variant="outlined"
              size="small"
              startIcon={<SortIcon />}
              onClick={() => {
                if (sortField === 'default') {
                  setSortField('change');
                  setSortDir('asc');
                } else if (sortField === 'change') {
                  if (sortDir === 'asc') {
                    setSortDir('desc');
                  } else {
                    setSortField('price');
                    setSortDir('asc');
                  }
                } else if (sortField === 'price') {
                  if (sortDir === 'asc') {
                    setSortDir('desc');
                  } else {
                    setSortField('default');
                    setSortDir('asc');
                  }
                }
              }}
              disabled={batchPlanning}
            >
              {sortField === 'default' ? '默认排序' : sortField === 'change' ? `涨幅${sortDir === 'asc' ? '↑' : '↓'}` : `价格${sortDir === 'asc' ? '↑' : '↓'}`}
            </Button>
            <Button
              variant="outlined"
              size="small"
              startIcon={refreshing ? <CircularProgress size={14} /> : <RefreshIcon />}
              onClick={() => void load(true)}
              disabled={refreshing || batchPlanning}
            >
              刷新
            </Button>
            <Button
              variant="outlined"
              size="small"
              startIcon={batchPlanning ? <CircularProgress size={14} /> : <AutoAwesomeIcon />}
              onClick={() => void createTopAiPlan()}
              disabled={batchPlanning || contracts.length === 0}
            >
              {batchPlanning ? '生成中' : '最高分计划'}
            </Button>
            </>
          }
        />

        <TradingStatusStrip risk={risk} account={account} pendingCount={pendingCount} />

        {error && <Alert severity="error">{error}</Alert>}

        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', sm: 'repeat(3, minmax(0, 1fr))' },
            gap: 1.5,
            '& > :last-child': { gridColumn: { xs: '1 / -1', sm: 'auto' } },
          }}
        >
          <MarketStat label="准入候选" value={contracts.length} helper={`当前持仓 ${heldContracts.length}`} />
          <MarketStat label="最高分候选" value={String(top?.instId ?? '--')} helper={`${formatTrend(top?.trendDirection)} · ${formatNumber(top?.score, 0)}分`} />
          <MarketStat label="持仓浮盈亏" value={formatUSDT(totalPnl)} helper="OKX未实现盈亏" color={pnlColor(totalPnl)} />
        </Box>

        <Alert severity="info">
          AI 在规则准入池内主导合约、方向和入场模式；规则继续计算价格、仓位并执行所有硬风控。
        </Alert>
        <Alert severity={aiSelection?.source === 'RULE_FALLBACK' ? 'warning' : 'info'}>
          {aiSelection?.source === 'AI_PRIMARY'
            ? `AI 主选 ${String(aiSelection.selectedInstId)} · ${formatSide(aiSelection.direction)} · ${String(aiSelection.entryMode)} · 置信度 ${formatPercent(Number(aiSelection.confidence ?? 0) * 100)}`
            : aiSelection?.source === 'AI_WAIT' ? 'AI 本轮选择等待，不会触发规则保底下单。'
              : aiSelection?.source === 'RULE_FALLBACK' ? `AI 不可用，已进入规则保底：${String(aiSelection.fallbackReason ?? '原因不可用')}`
                : 'AI 尚未完成本轮选择。'}
        </Alert>

        {loading ? (
          <PanelState loading sx={panel} />
        ) : (
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: {
                xs: '1fr',
                sm: 'repeat(2, minmax(0, 1fr))',
                lg: 'repeat(3, minmax(0, 1fr))',
                xl: 'repeat(4, minmax(0, 1fr))',
              },
              gap: 1.5,
            }}
          >
            {filtered.map((contract, index) => (
              <ContractCard
                key={String(contract.instId)}
                contract={contract}
                rank={index + 1}
                planning={planningInstId === contract.instId || batchPlanning}
                closing={Boolean(contract.position) && closingKey === positionKey(contract.position!)}
                onCreatePlan={() => void createAiPlan(String(contract.instId))}
                onClosePosition={() => contract.position && setCloseDialogPosition(contract.position)}
                onOpenChart={() => setChartInstId(String(contract.instId))}
              />
            ))}
            {filtered.length === 0 && (
              <PanelState sx={{ ...panel, gridColumn: '1 / -1' }}>当前没有满足数据准入条件的合约候选。</PanelState>
            )}
          </Box>
        )}
      </Stack>
      <CandleDialog instId={chartInstId} open={Boolean(chartInstId)} onClose={() => setChartInstId('')} />
      <ClosePositionDialog
        open={Boolean(closeDialogPosition)}
        position={closeDialogPosition}
        loading={Boolean(closingKey)}
        onCancel={() => setCloseDialogPosition(null)}
        onConfirm={() => void confirmClosePosition()}
      />
    </PageShell>
  );
}

function ContractCard({
  contract,
  rank,
  planning,
  closing,
  onCreatePlan,
  onClosePosition,
  onOpenChart,
}: {
  contract: CandidateWithPosition;
  rank: number;
  planning: boolean;
  closing: boolean;
  onCreatePlan: () => void;
  onClosePosition: () => void;
  onOpenChart: () => void;
}) {
  const change = Number(contract.changePercent24h);
  const position = contract.position ?? {};
  const held = Boolean(contract.position);
  const tone = directionTone(held ? position.posSide : contract.trendDirection);
  const trendText = held ? formatSide(position.posSide) : formatTrend(contract.trendDirection);
  const ChangeIcon = change > 0 ? TrendingUpIcon : TrendingDownIcon;

  return (
    <Box component="article" sx={{ ...panel, ...interactivePanel, position: 'relative', p: 2 }}>
      <ButtonBase
        aria-label={`${String(contract.instId ?? '--')} K线图`}
        onClick={onOpenChart}
        disableRipple
        sx={{ position: 'absolute', inset: 0, zIndex: 1, borderRadius: 1 }}
      />
      <Stack spacing={2}>
        <Stack direction="row" justifyContent="space-between" alignItems="flex-start" gap={1}>
          <Box minWidth={0}>
            <Stack direction="row" alignItems="center" gap={1}>
              <Chip size="small" label={`#${rank}`} sx={{ flexShrink: 0 }} />
              <Typography fontWeight={750} sx={{ overflowWrap: 'anywhere' }}>
                {String(contract.instId ?? '--')}
              </Typography>
            </Stack>
            <Chip
              size="small"
              icon={tone === 'success' ? <TrendingUpIcon /> : tone === 'error' ? <TrendingDownIcon /> : undefined}
              label={trendText}
              color={tone}
              sx={{ mt: 1 }}
              variant="outlined"
            />
          </Box>
          <ShowChartIcon fontSize="small" color="primary" aria-hidden />
        </Stack>

        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: 'minmax(0, 1fr) auto',
            gap: 1,
            alignItems: 'end',
          }}
        >
          <Box>
            <Typography variant="caption" color="text.secondary">
              {held ? '未实现盈亏' : '最新价格'}
            </Typography>
            <Typography sx={{ fontSize: '1.375rem', fontWeight: 750, color: held ? pnlColor(position.unrealizedPnl) : 'text.primary' }}>
              {held ? formatUSDT(position.unrealizedPnl) : formatPrice(contract.lastPrice)}
            </Typography>
          </Box>
          <Stack direction="row" alignItems="center" spacing={0.5} sx={{ color: pnlColor(change) }}>
            {Number.isFinite(change) && change !== 0 ? <ChangeIcon sx={{ fontSize: 18 }} /> : null}
            <Typography sx={{ fontWeight: 900 }}>
              {formatPercent(change)}
            </Typography>
          </Stack>
        </Box>

        <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 1 }}>
          <MiniMetric label="持仓数量" value={held ? formatNumber(position.size) : '未持仓'} />
          <MiniMetric label="持仓均价" value={held ? formatPrice(position.avgPrice) : '不可用'} />
          <MiniMetric label="真实保证金" value={held ? formatUSDT(position.margin) : '不可用'} />
          <MiniMetric label="保证金模式" value={held ? `${formatSide(position.marginMode)} ${formatLeverage(position.leverage)}` : '不可用'} />
          <MiniMetric label="最新价" value={formatPrice(contract.lastPrice)} />
          <MiniMetric label="综合评分" value={formatNumber(contract.score, 0)} />
          <MiniMetric label="建议杠杆" value={formatLeverage(contract.suggestedLeverage)} />
          <MiniMetric label="24h成交量" value={formatVolume(contract.volume24h)} />
          <MiniMetric label="量能放大" value={`${formatNumber(contract.volumeSpikeRatio, 2)}x`} />
          <MiniMetric label="方向" value={formatTrend(contract.trendDirection)} />
        </Box>

        <Stack direction="row" gap={1} flexWrap="wrap" sx={{ minHeight: 26 }}>
          {((contract.candidateReasonList as string[]) ?? []).slice(0, 2).map((item) => (
            <Chip key={item} size="small" label={item} variant="outlined" sx={{ maxWidth: '100%' }} />
          ))}
          {((contract.riskTagList as string[]) ?? []).slice(0, 1).map((item) => (
            <Chip key={item} size="small" color="warning" label={item} variant="outlined" sx={{ maxWidth: '100%' }} />
          ))}
        </Stack>
        <Stack direction="row" gap={1} flexWrap="wrap" sx={{ pt: 1, borderTop: 1, borderColor: 'divider', '& .MuiButton-root': { position: 'relative', zIndex: 2 } }}>
          {held && <Button
            size="small"
            color="error"
            variant="outlined"
            startIcon={closing ? <CircularProgress size={12} color="inherit" /> : <OutputIcon />}
            disabled={closing}
            onClick={onClosePosition}
            sx={{ minWidth: 72 }}
          >
            {closing ? '提交中' : '平仓'}
          </Button>}
          <Button
            size="small"
            variant="contained"
            startIcon={planning ? <CircularProgress size={12} /> : <AutoAwesomeIcon />}
            disabled={planning}
            onClick={onCreatePlan}
            sx={{ minWidth: 88 }}
          >
            {planning ? '推理中' : 'AI计划'}
          </Button>
        </Stack>
      </Stack>
    </Box>
  );
}

function CandleDialog({ instId, open, onClose }: { instId: string; open: boolean; onClose: () => void }) {
  const notify = useNotify();
  const [bar, setBar] = useState('15m');
  const [candles, setCandles] = useState<Candle[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!open || !instId) {
      return;
    }
    let active = true;
    let firstLoad = true;
    let requestRunning = false;
    async function loadCandles() {
      if (requestRunning) return;
      requestRunning = true;
      if (firstLoad) {
        setLoading(true);
      }
      try {
        const data = await quantApi.contractCandles(instId, bar);
        if (active) {
          setCandles(Array.isArray(data) ? (data as Candle[]) : []);
        }
      } catch (error) {
        if (active) {
          notify(error instanceof Error ? error.message : 'K线加载失败', { type: 'error' });
          setCandles([]);
        }
      } finally {
        requestRunning = false;
        if (active) {
          setLoading(false);
          firstLoad = false;
        }
      }
    }
    void loadCandles();
    const timer = window.setInterval(() => void loadCandles(), 5000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [bar, instId, notify, open]);

  const trend = candles.length >= 2 && Number(candles[candles.length - 1].close) >= Number(candles[0].open)
    ? 'BULLISH'
    : 'BEARISH';
  const positive = trend === 'BULLISH';

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="lg">
      <DialogTitle>
        <Box sx={{ pr: 6 }}>
          <Typography component="span" variant="h6">{instId} K线图</Typography>
          <Typography display="block" variant="caption" color={positive ? 'success.main' : 'error.main'}>
            {positive ? '多头结构更明显' : '空头结构更明显'}
          </Typography>
        </Box>
        <IconButton aria-label="关闭" onClick={onClose} sx={{ position: 'absolute', right: 8, top: 8 }}>
          <CloseIcon fontSize="small" />
        </IconButton>
      </DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          <ToggleButtonGroup
            size="small"
            exclusive
            value={bar}
            aria-label="K线周期"
            onChange={(_, value) => value && setBar(value)}
            sx={{
              display: 'flex',
              flexWrap: 'wrap',
              gap: 0.5,
              '& .MuiToggleButtonGroup-grouped': { m: 0, px: 1, border: 1, borderColor: 'divider', borderRadius: 1 },
            }}
          >
            {candlePeriods.map((period) => (
              <ToggleButton key={period.bar} value={period.bar}>{period.label}</ToggleButton>
            ))}
          </ToggleButtonGroup>
          {loading ? <PanelState loading sx={insetPanel} /> : <CandleChart candles={candles} bar={bar} />}
        </Stack>
      </DialogContent>
    </Dialog>
  );
}

function CandleChart({ candles, bar }: { candles: Candle[]; bar: string }) {
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
      <PanelState sx={insetPanel}>暂无K线数据</PanelState>
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
    <Box ref={chartRef} sx={{ ...panel, overflow: 'hidden' }}>
      <Stack direction="row" flexWrap="wrap" gap={1} alignItems="baseline" sx={{ px: 1.5, py: 1, borderBottom: 1, borderColor: 'divider' }}>
        <Typography variant="caption" color="text.secondary">实时最新价</Typography>
        <Typography fontWeight={750}>{formatPrice(last.close)}</Typography>
        <Typography variant="body2" fontWeight={700} color={trendPositive ? 'success.main' : 'error.main'}>
          {formatPercent(changePercent)}
        </Typography>
      </Stack>
      <svg viewBox={`0 0 ${width} ${height}`} width="100%" height={height} role="img" aria-label="价格K线及成交量" style={{ display: 'block' }}>
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
          分时量
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
    </Box>
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

function MarketStat({ label, value, helper, color }: { label: string; value: unknown; helper: string; color?: string }) {
  return (
    <Box sx={{ ...insetPanel, p: 2, bgcolor: 'background.paper' }}>
      <Typography variant="caption" color="text.secondary" fontWeight={800}>
        {label}
      </Typography>
      <Typography color={color} sx={{ mt: 0.5, fontSize: '1.25rem', fontWeight: 750, overflowWrap: 'anywhere' }}>{String(value)}</Typography>
      <Typography variant="caption" color="text.secondary">
        {helper}
      </Typography>
    </Box>
  );
}

function MiniMetric({ label, value }: { label: string; value: string }) {
  return (
    <Box sx={{ ...insetPanel, p: 1 }}>
      <Typography variant="caption" color="text.secondary" fontWeight={800}>
        {label}
      </Typography>
      <Typography sx={{ mt: 0.5, fontWeight: 900 }}>{value}</Typography>
    </Box>
  );
}
