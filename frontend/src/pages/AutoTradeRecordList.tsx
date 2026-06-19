import RefreshIcon from '@mui/icons-material/Refresh';
import SearchIcon from '@mui/icons-material/Search';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  FormControl,
  InputAdornment,
  MenuItem,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useMemo, useState } from 'react';
import { useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { glassCard } from '../components/glass';
import { PageHeader, PageShell } from '../components/PageShell';
import { StatusChip } from '../components/StatusChip';
import { formatAction, formatLeverage, formatNumber, formatPrice, formatSide, formatStatus, formatUSDT } from '../formatters';

type AutoTradeRecord = Record<string, unknown>;

type AutoTradePage = {
  items: AutoTradeRecord[];
  total: number;
  page: number;
  size: number;
};

const pageSize = 50;
const statusOptions = [
  { value: '', label: '全部状态' },
  { value: 'EXECUTED', label: '已提交委托' },
  { value: 'ENTRY_SUBMITTED', label: '入场委托已提交' },
  { value: 'SKIPPED', label: '已跳过' },
  { value: 'REJECTED', label: '已拒绝' },
  { value: 'FAILED', label: '失败' },
  { value: 'UNKNOWN_SUBMIT_STATUS', label: '提交未知' },
  { value: 'PROTECTION_FAILED', label: '保护单失败' },
  { value: 'CLOSE_SUBMITTED', label: '平仓已提交' },
  { value: 'CLOSED', label: '已关闭' },
];

const emptyPage: AutoTradePage = {
  items: [],
  total: 0,
  page: 0,
  size: pageSize,
};

export function AutoTradeRecordList() {
  const notify = useNotify();
  const [data, setData] = useState<AutoTradePage>(emptyPage);
  const [status, setStatus] = useState('');
  const [instId, setInstId] = useState('');
  const [positions, setPositions] = useState<Record<string, unknown>[]>([]);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');

  async function load(nextPage = page, silent = false) {
    if (silent) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    try {
      const [recordData, positionData] = await Promise.all([
        quantApi.autoTradeRecords({
          status,
          instId: instId.trim(),
          page: nextPage,
          size: pageSize,
        }),
        quantApi.positions(),
      ]);
      const result = recordData as Record<string, unknown>;
      setData({
        items: Array.isArray(result.items) ? (result.items as AutoTradeRecord[]) : [],
        total: Number(result.total ?? 0),
        page: Number(result.page ?? nextPage),
        size: Number(result.size ?? pageSize),
      });
      setPositions(Array.isArray(positionData) ? (positionData as Record<string, unknown>[]) : []);
      setPage(nextPage);
      setError('');
    } catch (loadError) {
      const message = loadError instanceof Error ? loadError.message : '自动交易记录加载失败';
      setError(message);
      if (!silent) {
        notify(message, { type: 'error' });
      }
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }

  useEffect(() => {
    void load(0);
  }, []);

  const summary = useMemo(() => {
    const executed = data.items.filter((item) => item.status === 'EXECUTED').length;
    const pnl = data.items.reduce((sum, item) => {
      const position = positionFor(item, positions);
      return sum + Number(position?.unrealizedPnl ?? 0);
    }, 0);
    return { executed, pnl };
  }, [data.items, positions]);

  const hasNext = (page + 1) * pageSize < data.total;

  return (
    <PageShell title="自动交易记录">
      <Stack spacing={2}>
        <PageHeader
          title="自动交易记录"
          subtitle={`共 ${data.total} 条复盘记录，当前页委托 ${summary.executed} 条，当前持仓浮盈亏 ${formatUSDT(summary.pnl)}`}
          eyebrow="Auto Trade Audit"
          actions={
            <>
              <FormControl size="small" sx={{ minWidth: { xs: 1, sm: 150 } }}>
                <Select value={status} onChange={(event) => setStatus(event.target.value)}>
                  {statusOptions.map((option) => (
                    <MenuItem key={option.value || 'all'} value={option.value}>
                      {option.label}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
              <TextField
                size="small"
                value={instId}
                onChange={(event) => setInstId(event.target.value)}
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
                startIcon={<SearchIcon />}
                onClick={() => void load(0)}
                disabled={refreshing}
              >
                查询
              </Button>
              <Button
                variant="outlined"
                size="small"
                startIcon={refreshing ? <CircularProgress size={14} /> : <RefreshIcon />}
                onClick={() => void load(page, true)}
                disabled={refreshing}
              >
                刷新
              </Button>
            </>
          }
        />

        {error ? <Alert severity="error">{error}</Alert> : null}

        <Box sx={{ ...glassCard }}>
          {loading ? (
            <Box sx={{ minHeight: 260, display: 'grid', placeItems: 'center' }}>
              <CircularProgress size={28} />
            </Box>
          ) : data.items.length === 0 ? (
            <Box sx={{ minHeight: 220, display: 'grid', placeItems: 'center', p: 3 }}>
              <Typography color="text.secondary">暂无自动交易记录。</Typography>
            </Box>
          ) : (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>时间</TableCell>
                    <TableCell>状态</TableCell>
                    <TableCell>合约</TableCell>
                    <TableCell>开仓方向</TableCell>
                    <TableCell align="right">评分</TableCell>
                    <TableCell align="right">保证金</TableCell>
                    <TableCell align="right">杠杆</TableCell>
                    <TableCell align="right">计划入场</TableCell>
                    <TableCell align="right">持仓均价</TableCell>
                    <TableCell align="right">未实现盈亏</TableCell>
                    <TableCell align="right">盈亏比</TableCell>
                    <TableCell>阶段</TableCell>
                    <TableCell>原因</TableCell>
                    <TableCell>OKX订单号</TableCell>
                    <TableCell>说明</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.items.map((record) => {
                    const position = positionFor(record, positions);
                    return (
                      <TableRow key={String(record.id)} hover>
                        <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatTime(record.createdAt)}</TableCell>
                        <TableCell>
                          <StatusChip value={String(record.status ?? '-')} />
                        </TableCell>
                        <TableCell sx={{ fontWeight: 900 }}>{String(record.instId ?? '-')}</TableCell>
                        <TableCell>{formatAction(record.action)} / {formatSide(record.posSide)}</TableCell>
                        <TableCell align="right">{formatNumber(record.candidateScore, 0)}</TableCell>
                        <TableCell align="right">{formatUSDT(record.marginAmount)}</TableCell>
                        <TableCell align="right">{formatLeverage(record.leverage)}</TableCell>
                        <TableCell align="right">{formatPrice(record.entryPrice ?? record.lastPrice)}</TableCell>
                        <TableCell align="right">{formatPrice(position?.avgPrice)}</TableCell>
                        <TableCell align="right">{formatUSDT(position?.unrealizedPnl)}</TableCell>
                        <TableCell align="right">{formatNumber(record.riskRewardRatio, 2)}</TableCell>
                        <TableCell>{String(record.stage ?? '-')}</TableCell>
                        <TableCell sx={{ minWidth: 180, maxWidth: 320, overflowWrap: 'anywhere' }}>
                          {String(record.reasonCode ?? record.reasonMessage ?? '-')}
                        </TableCell>
                        <TableCell sx={{ fontFamily: '"JetBrains Mono", monospace', maxWidth: 150, overflowWrap: 'anywhere' }}>
                          {String(record.okxOrderId ?? '-')}
                        </TableCell>
                        <TableCell sx={{ minWidth: 260, maxWidth: 420, overflowWrap: 'anywhere' }}>
                          {String(record.message ?? '-')}
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </TableContainer>
          )}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} justifyContent="space-between" alignItems="center" sx={{ p: 1.5 }}>
            <Typography variant="caption" color="text.secondary" fontWeight={700}>
              第 {page + 1} 页，每页 {pageSize} 条，状态筛选：{formatStatus(status || '全部')}
            </Typography>
            <Stack direction="row" spacing={1}>
              <Button size="small" variant="outlined" disabled={page <= 0 || refreshing} onClick={() => void load(page - 1, true)}>
                上一页
              </Button>
              <Button size="small" variant="outlined" disabled={!hasNext || refreshing} onClick={() => void load(page + 1, true)}>
                下一页
              </Button>
            </Stack>
          </Stack>
        </Box>
      </Stack>
    </PageShell>
  );
}

function positionFor(record: AutoTradeRecord, positions: Record<string, unknown>[]) {
  return positions.find((position) =>
    String(position.instId ?? '') === String(record.instId ?? '')
      && (!record.posSide || String(position.posSide ?? '') === String(record.posSide ?? '')),
  );
}

function formatTime(value: unknown) {
  const date = new Date(String(value ?? ''));
  if (Number.isNaN(date.getTime())) {
    return '-';
  }
  return date.toLocaleString();
}
