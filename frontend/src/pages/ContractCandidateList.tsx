import AutoAwesomeIcon from '@mui/icons-material/AutoAwesome';
import CloseIcon from '@mui/icons-material/Close';
import RefreshIcon from '@mui/icons-material/Refresh';
import SearchIcon from '@mui/icons-material/Search';
import SortIcon from '@mui/icons-material/Sort';
import TrendingDownIcon from '@mui/icons-material/TrendingDown';
import TrendingUpIcon from '@mui/icons-material/TrendingUp';
import {
  Alert,
  Box,
  Button,
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
import { useEffect, useMemo, useState } from 'react';
import { Title, useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { TradingStatusStrip } from '../components/TradingStatusStrip';
import { formatPercent, formatPrice, formatTrend, formatVolume } from '../formatters';

type ContractCandidate = Record<string, unknown>;
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
  const [risk, setRisk] = useState<Record<string, unknown>>();
  const [account, setAccount] = useState<Record<string, unknown>>();
  const [pendingCount, setPendingCount] = useState(0);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [planningInstId, setPlanningInstId] = useState('');
  const [batchPlanning, setBatchPlanning] = useState(false);
  const [chartInstId, setChartInstId] = useState('');
  const [error, setError] = useState('');
  const [lastUpdatedAt, setLastUpdatedAt] = useState<Date | null>(null);
  const [sortField, setSortField] = useState<SortField>('default');
  const [sortDir, setSortDir] = useState<SortDir>('asc');

  async function load(silent = false) {
    if (silent) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    try {
      const [contractData, riskData, accountData, orderData] = await Promise.all([
        quantApi.contractCandidates(),
        quantApi.riskStatus(),
        quantApi.accountSummary(),
        quantApi.pendingOrders(),
      ]);
      setContracts(Array.isArray(contractData) ? (contractData as ContractCandidate[]) : []);
      setRisk(riskData as Record<string, unknown>);
      setAccount(accountData as Record<string, unknown>);
      setPendingCount(Array.isArray(orderData) ? orderData.length : 0);
      setLastUpdatedAt(new Date());
      setError('');
    } catch (loadError) {
      const message = loadError instanceof Error ? loadError.message : '合约数据加载失败';
      setError(message);
      if (!silent) {
        notify(message, { type: 'error' });
      }
    } finally {
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

  async function createBatchAiPlan() {
    if (contracts.length === 0) {
      notify('当前没有合约可生成计划', { type: 'warning' });
      return;
    }
    setBatchPlanning(true);
    try {
      const instIds = contracts.map((c) => String(c.instId));
      const result = (await quantApi.createPendingOrderBatch(instIds)) as Record<string, unknown>;
      const created = Array.isArray(result.created) ? result.created.length : 0;
      const errors = Array.isArray(result.errors) ? result.errors.length : 0;
      notify(`批量AI计划完成：成功 ${created} 个${errors > 0 ? `，失败 ${errors} 个` : ''}，请到待确认订单查看`, { type: 'success' });
      await load(true);
    } catch (planError) {
      notify(planError instanceof Error ? planError.message : '批量AI计划生成失败', { type: 'error', multiLine: true });
    } finally {
      setBatchPlanning(false);
    }
  }

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => void load(true), 2000);
    return () => window.clearInterval(timer);
  }, []);

  const sorted = useMemo(() => {
    if (sortField === 'default') return contracts;
    const dir = sortDir === 'asc' ? 1 : -1;
    return [...contracts].sort((a, b) => {
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
  }, [contracts, sortField, sortDir]);

  const filtered = useMemo(() => {
    const text = query.trim().toLowerCase();
    if (!text) {
      return sorted;
    }
    return sorted.filter((item) =>
      [item.instId, item.baseCurrency, item.trendDirection]
        .some((value) => String(value ?? '').toLowerCase().includes(text)),
    );
  }, [sorted, query]);

  const top = contracts[0] as ContractCandidate | undefined;

  return (
    <Box sx={{ p: { xs: 2, md: 3 }, minHeight: '100%' }}>
      <Title title="OKX合约机会池" />
      <Stack spacing={2.25}>
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', lg: 'minmax(0, 1fr) 360px' },
            gap: 2,
            alignItems: 'stretch',
          }}
        >
          <Box>
            <Typography variant="h5" fontWeight={900}>
              OKX合约机会池
            </Typography>
            <Typography color="text.secondary" variant="body2" sx={{ mt: 0.75 }}>
              {lastUpdatedAt ? `行情刷新 ${lastUpdatedAt.toLocaleTimeString()}` : '行情准备中'}
              {refreshing ? ' · 更新中' : ''}
            </Typography>
          </Box>

          <Stack direction="row" spacing={1} justifyContent={{ xs: 'flex-start', lg: 'flex-end' }} alignItems="center">
            <TextField
              size="small"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索合约"
              InputProps={{
                startAdornment: (
                  <InputAdornment position="start">
                    <SearchIcon fontSize="small" />
                  </InputAdornment>
                ),
              }}
              sx={{ minWidth: { xs: 1, sm: 220 } }}
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
              onClick={() => void createBatchAiPlan()}
              disabled={batchPlanning || contracts.length === 0}
            >
              {batchPlanning ? '推理中' : '一键AI计划'}
            </Button>
          </Stack>
        </Box>

        <TradingStatusStrip risk={risk} account={account} pendingCount={pendingCount} />

        {error && <Alert severity="error">{error}</Alert>}

        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: '1.1fr 0.9fr 0.9fr' },
            gap: 1.5,
          }}
        >
          <MarketStat label="机会数量" value={contracts.length} helper="AI候选上限 20" />
          <MarketStat label="首位合约" value={String(top?.instId ?? '--')} helper={formatTrend(top?.trendDirection)} />
          <MarketStat label="24h成交量" value={formatVolume(top?.volume24h)} helper="按成交量排序" />
        </Box>

        {loading ? (
          <Box sx={{ py: 8, display: 'grid', placeItems: 'center' }}>
            <CircularProgress size={28} />
          </Box>
        ) : (
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: {
                xs: '1fr',
                md: 'repeat(2, minmax(0, 1fr))',
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
                onCreatePlan={() => void createAiPlan(String(contract.instId))}
                onOpenChart={() => setChartInstId(String(contract.instId))}
              />
            ))}
          </Box>
        )}
      </Stack>
      <CandleDialog instId={chartInstId} open={Boolean(chartInstId)} onClose={() => setChartInstId('')} />
    </Box>
  );
}

function ContractCard({
  contract,
  rank,
  planning,
  onCreatePlan,
  onOpenChart,
}: {
  contract: ContractCandidate;
  rank: number;
  planning: boolean;
  onCreatePlan: () => void;
  onOpenChart: () => void;
}) {
  const change = Number(contract.changePercent24h);
  const positive = change >= 0;
  const TrendIcon = positive ? TrendingUpIcon : TrendingDownIcon;
  const trendText = positive ? '多头趋势' : '空头趋势';
  const trendColor = positive ? '#00d4aa' : '#ef4444';

  return (
    <Box
      role="button"
      tabIndex={0}
      onClick={onOpenChart}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          onOpenChart();
        }
      }}
      sx={{
        width: '100%',
        textAlign: 'left',
        p: 1.5,
        border: `1px solid ${positive ? 'rgba(0, 212, 170, 0.34)' : 'rgba(239, 68, 68, 0.34)'}`,
        borderLeft: `4px solid ${trendColor}`,
        borderRadius: 1,
        bgcolor: positive ? 'rgba(4, 120, 87, 0.10)' : 'rgba(127, 29, 29, 0.10)',
        color: 'text.primary',
        cursor: 'pointer',
        transition: 'transform 160ms ease, border-color 160ms ease, background 160ms ease',
        '&:hover': {
          transform: 'translateY(-2px)',
          borderColor: positive ? 'rgba(0, 212, 170, 0.52)' : 'rgba(239, 68, 68, 0.52)',
          bgcolor: 'rgba(17, 24, 39, 0.96)',
        },
      }}
    >
      <Stack spacing={1.25}>
        <Stack direction="row" justifyContent="space-between" alignItems="flex-start" gap={1}>
          <Box minWidth={0}>
            <Stack direction="row" alignItems="center" spacing={0.75}>
              <Chip size="small" label={`#${rank}`} sx={{ height: 22 }} />
              <Typography fontWeight={900} noWrap>
                {String(contract.instId ?? '--')}
              </Typography>
            </Stack>
            <Chip
              size="small"
              icon={<TrendIcon />}
              label={trendText}
              sx={{
                mt: 0.75,
                height: 22,
                color: trendColor,
                borderColor: `${trendColor}66`,
                bgcolor: positive ? 'rgba(0, 212, 170, 0.10)' : 'rgba(239, 68, 68, 0.10)',
                '& .MuiChip-icon': { color: trendColor, fontSize: 14 },
              }}
              variant="outlined"
            />
          </Box>
          <Button
            size="small"
            variant="contained"
            startIcon={planning ? <CircularProgress size={12} /> : <AutoAwesomeIcon />}
            disabled={planning}
            onClick={(event) => {
              event.stopPropagation();
              onCreatePlan();
            }}
            sx={{ minWidth: 88 }}
          >
            {planning ? '推理中' : 'AI计划'}
          </Button>
        </Stack>

        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: '1fr auto',
            gap: 1,
            alignItems: 'end',
          }}
        >
          <Box>
            <Typography variant="caption" color="text.secondary">
              最新价
            </Typography>
            <Typography sx={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 22, fontWeight: 900 }}>
              {formatPrice(contract.lastPrice)}
            </Typography>
          </Box>
          <Stack direction="row" alignItems="center" spacing={0.5} sx={{ color: positive ? 'success.main' : 'error.main' }}>
            <TrendIcon sx={{ fontSize: 18 }} />
            <Typography sx={{ fontFamily: '"JetBrains Mono", monospace', fontWeight: 900 }}>
              {formatPercent(contract.changePercent24h)}
            </Typography>
          </Stack>
        </Box>

        <Box sx={{ display: 'grid', gridTemplateColumns: '1fr', gap: 1 }}>
          <MiniMetric label="24h成交量" value={formatVolume(contract.volume24h)} />
        </Box>

        <Stack direction="row" gap={0.75} flexWrap="wrap" sx={{ minHeight: 26 }}>
          {((contract.candidateReasonList as string[]) ?? []).slice(0, 2).map((item) => (
            <Chip key={item} size="small" label={item} variant="outlined" sx={{ maxWidth: '100%' }} />
          ))}
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
    async function loadCandles() {
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
        if (active) {
          setLoading(false);
          firstLoad = false;
        }
      }
    }
    void loadCandles();
    const timer = window.setInterval(() => void loadCandles(), 1000);
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
        <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ xs: 'flex-start', sm: 'center' }} gap={1} sx={{ pr: 5 }}>
          <Box>
            <Typography fontWeight={900}>{instId} K线图</Typography>
            <Typography variant="caption" sx={{ color: positive ? 'success.main' : 'error.main', fontWeight: 900 }}>
              {positive ? '多头结构更明显' : '空头结构更明显'}
            </Typography>
          </Box>
          <ToggleButtonGroup
            size="small"
            exclusive
            value={bar}
            onChange={(_, value) => value && setBar(value)}
          >
            {candlePeriods.map((period) => (
              <ToggleButton key={period.bar} value={period.bar}>
                {period.label}
              </ToggleButton>
            ))}
          </ToggleButtonGroup>
        </Stack>
        <IconButton
          aria-label="关闭"
          onClick={onClose}
          size="small"
          sx={{ position: 'absolute', right: 14, top: 14 }}
        >
          <CloseIcon fontSize="small" />
        </IconButton>
      </DialogTitle>
      <DialogContent>
        <Box sx={{ height: { xs: 360, md: 520 }, position: 'relative' }}>
          {loading ? (
            <Box sx={{ height: 1, display: 'grid', placeItems: 'center' }}>
              <CircularProgress size={28} />
            </Box>
          ) : (
            <CandleChart candles={candles} bar={bar} />
          )}
        </Box>
      </DialogContent>
    </Dialog>
  );
}

function CandleChart({ candles, bar }: { candles: Candle[]; bar: string }) {
  const width = 1000;
  const height = 460;
  const padding = { top: 18, right: 72, bottom: 32, left: 18 };
  const priceHeight = 318;
  const volumeTop = 354;
  const volumeHeight = 72;
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
      <Box sx={{ height: 1, display: 'grid', placeItems: 'center', color: 'text.secondary' }}>
        暂无K线数据
      </Box>
    );
  }

  const min = Math.min(...data.map((item) => item.low));
  const max = Math.max(...data.map((item) => item.high));
  const range = max - min || 1;
  const plotWidth = width - padding.left - padding.right;
  const plotHeight = priceHeight - padding.top;
  const step = plotWidth / data.length;
  const candleWidth = Math.max(3, Math.min(10, step * 0.58));
  const y = (price: number) => padding.top + ((max - price) / range) * plotHeight;
  const maxVolume = Math.max(...data.map((item) => item.volume), 1);
  const volumeY = (volume: number) => volumeTop + volumeHeight - (volume / maxVolume) * volumeHeight;
  const first = data[0];
  const last = data[data.length - 1];
  const trendPositive = last.close >= first.open;
  const changePercent = first.open ? ((last.close - first.open) / first.open) * 100 : 0;
  const grid = Array.from({ length: 5 }, (_, index) => min + (range * index) / 4);
  const timeTicks = timeTickIndexes(data.length).map((index) => ({ index, item: data[index] }));
  const trendLine = regressionTrendLine(data, padding.left, step, y);

  return (
    <Box sx={{ height: 1, width: 1, overflow: 'hidden', border: '1px solid rgba(148, 163, 184, 0.12)', borderRadius: 1 }}>
      <svg viewBox={`0 0 ${width} ${height}`} width="100%" height="100%" preserveAspectRatio="none" role="img">
        <rect x="0" y="0" width={width} height={height} fill="#070b12" />
        <g>
          <rect x={padding.left} y={padding.top} width="240" height="72" rx="6" fill="rgba(2,6,23,0.82)" stroke="rgba(148,163,184,0.18)" />
          <text x={padding.left + 12} y={padding.top + 20} fill="#94a3b8" fontSize="12">
            实时最新价
          </text>
          <text x={padding.left + 12} y={padding.top + 48} fill="#f8fafc" fontSize="18" fontWeight="800">
            {formatPrice(last.close)}
          </text>
          <text x={padding.left + 12} y={padding.top + 66} fill={trendPositive ? '#00d4aa' : '#ef4444'} fontSize="14" fontWeight="800">
            {formatPercent(changePercent)}
          </text>
        </g>
        {grid.map((price) => {
          const lineY = y(price);
          return (
            <g key={price}>
              <line x1={padding.left} y1={lineY} x2={width - padding.right} y2={lineY} stroke="rgba(148,163,184,0.12)" strokeWidth="1" />
              <text x={width - padding.right + 8} y={lineY + 4} fill="#94a3b8" fontSize="12">
                {formatPrice(price)}
              </text>
            </g>
          );
        })}
        <line x1={padding.left} y1={priceHeight} x2={width - padding.right} y2={priceHeight} stroke="rgba(148,163,184,0.18)" strokeWidth="1" />
        <line x1={padding.left} y1={volumeTop - 10} x2={width - padding.right} y2={volumeTop - 10} stroke="rgba(148,163,184,0.12)" strokeWidth="1" />
        <text x={padding.left} y={volumeTop - 18} fill="#94a3b8" fontSize="12">
          分时量
        </text>
        <path
          d={data.map((item, index) => `${index === 0 ? 'M' : 'L'} ${padding.left + index * step + step / 2} ${y(item.close)}`).join(' ')}
          fill="none"
          stroke={trendPositive ? '#00d4aa' : '#ef4444'}
          strokeWidth="2"
          opacity="0.42"
        />
        {trendLine && (
          <line
            x1={trendLine.x1}
            y1={trendLine.y1}
            x2={trendLine.x2}
            y2={trendLine.y2}
            stroke={trendPositive ? '#7dd3fc' : '#fbbf24'}
            strokeWidth="2.4"
            strokeDasharray="8 6"
            opacity="0.92"
          />
        )}
        {data.map((item, index) => {
          const x = padding.left + index * step + step / 2;
          const up = item.close >= item.open;
          const color = up ? '#00d4aa' : '#ef4444';
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
                fill={up ? 'rgba(0,212,170,0.9)' : 'rgba(239,68,68,0.9)'}
              />
              <rect
                x={x - candleWidth / 2}
                y={volumeY(item.volume)}
                width={candleWidth}
                height={Math.max(1, volumeTop + volumeHeight - volumeY(item.volume))}
                fill={up ? 'rgba(0,212,170,0.38)' : 'rgba(239,68,68,0.38)'}
              />
            </g>
          );
        })}
        {timeTicks.map(({ index, item }) => {
          const x = padding.left + index * step + step / 2;
          return (
            <g key={`time-${item.timestamp}`}>
              <line x1={x} y1={padding.top} x2={x} y2={volumeTop + volumeHeight} stroke="rgba(148,163,184,0.07)" strokeWidth="1" />
              <text x={x} y={height - 10} fill="#94a3b8" fontSize="12" textAnchor="middle">
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

function timeTickIndexes(length: number) {
  if (length <= 1) {
    return [0];
  }
  const tickCount = Math.min(6, length);
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

function MarketStat({ label, value, helper }: { label: string; value: unknown; helper: string }) {
  return (
    <Box sx={{ p: 1.5, border: '1px solid rgba(148, 163, 184, 0.14)', borderRadius: 1, bgcolor: 'rgba(2, 6, 23, 0.5)' }}>
      <Typography variant="caption" color="text.secondary" fontWeight={800}>
        {label}
      </Typography>
      <Typography sx={{ mt: 0.5, fontSize: 22, fontWeight: 900, overflowWrap: 'anywhere' }}>{String(value)}</Typography>
      <Typography variant="caption" color="text.secondary">
        {helper}
      </Typography>
    </Box>
  );
}

function MiniMetric({ label, value }: { label: string; value: string }) {
  return (
    <Box sx={{ p: 1, borderRadius: 1, bgcolor: 'rgba(2, 6, 23, 0.38)' }}>
      <Typography variant="caption" color="text.secondary" fontWeight={800}>
        {label}
      </Typography>
      <Typography sx={{ mt: 0.25, fontFamily: '"JetBrains Mono", monospace', fontWeight: 900 }}>{value}</Typography>
    </Box>
  );
}
