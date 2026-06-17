import CancelIcon from '@mui/icons-material/Cancel';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import RefreshIcon from '@mui/icons-material/Refresh';
import SearchIcon from '@mui/icons-material/Search';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  InputAdornment,
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
import { Confirm, useNotify } from 'react-admin';
import { useEffect, useMemo, useState } from 'react';
import { quantApi } from '../api/quantApi';
import { compactGlass, glassCard } from '../components/glass';
import { PageHeader, PageShell } from '../components/PageShell';
import { PendingOrderReviewDialog } from '../components/PendingOrderReviewDialog';
import { StatusChip } from '../components/StatusChip';
import { TradingStatusStrip } from '../components/TradingStatusStrip';
import { formatAction, formatLeverage, formatPrice, formatSide } from '../formatters';

type PendingOrderState = {
  risk?: Record<string, unknown>;
  account?: Record<string, unknown>;
  orders: Record<string, unknown>[];
};

const emptyState: PendingOrderState = {
  orders: [],
};

const PendingOrderActions = ({
  record,
  onChanged,
}: {
  record: Record<string, unknown>;
  onChanged: () => void;
}) => {
  const notify = useNotify();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const id = String(record.id);

  async function confirmOrder(marginAmount: string) {
    setSubmitting(true);
    try {
      const result = (await quantApi.confirmOrder(id, marginAmount)) as Record<string, unknown>;
      if (result.executed !== true) {
        throw new Error(String(result.message ?? '订单未提交OKX'));
      }
      notify(String(result.message ?? 'OKX委托已提交，等待成交，请到OKX当前委托/持仓确认'), { type: 'info' });
      onChanged();
    } catch (error) {
      notify(error instanceof Error ? error.message : '确认订单失败', { type: 'error' });
    } finally {
      setSubmitting(false);
      setConfirmOpen(false);
    }
  }

  async function cancelOrder() {
    setSubmitting(true);
    try {
      await quantApi.cancelOrder(id);
      notify('订单已取消', { type: 'success' });
      onChanged();
    } catch (error) {
      notify(error instanceof Error ? error.message : '取消订单失败', { type: 'error' });
    } finally {
      setSubmitting(false);
      setCancelOpen(false);
    }
  }

  return (
    <Stack direction="row" gap={0.75} justifyContent="flex-end">
      <Button
        size="small"
        variant="contained"
        color="warning"
        startIcon={<CheckCircleIcon />}
        onClick={() => setConfirmOpen(true)}
      >
        实盘确认
      </Button>
      <Button size="small" variant="outlined" color="warning" startIcon={<CancelIcon />} onClick={() => setCancelOpen(true)}>
        取消
      </Button>
      <PendingOrderReviewDialog
        open={confirmOpen}
        record={record}
        submitting={submitting}
        onConfirm={(marginAmount) => void confirmOrder(marginAmount)}
        onClose={() => setConfirmOpen(false)}
      />
      <Confirm
        isOpen={cancelOpen}
        title="取消待确认订单"
        content={`取消 ${String(record.instId)} 的待确认订单。`}
        confirm="取消订单"
        cancel="返回"
        onConfirm={() => void cancelOrder()}
        onClose={() => setCancelOpen(false)}
      />
    </Stack>
  );
};

export function PendingOrderList() {
  const notify = useNotify();
  const [state, setState] = useState<PendingOrderState>(emptyState);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [query, setQuery] = useState('');
  const [error, setError] = useState('');

  async function load(silent = false) {
    if (silent) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    try {
      const [risk, account, orders] = await Promise.all([
        quantApi.riskStatus(),
        quantApi.accountSummary(),
        quantApi.pendingOrders(),
      ]);
      setState({
        risk: risk as Record<string, unknown>,
        account: account as Record<string, unknown>,
        orders: Array.isArray(orders) ? (orders as Record<string, unknown>[]) : [],
      });
      setError('');
    } catch (loadError) {
      const message = loadError instanceof Error ? loadError.message : '待确认订单加载失败';
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
    void load();
  }, []);

  const filtered = useMemo(() => {
    const text = query.trim().toLowerCase();
    const orders = [...state.orders].sort((left, right) =>
      String(right.createdAt ?? '').localeCompare(String(left.createdAt ?? '')),
    );
    if (!text) {
      return orders;
    }
    return orders.filter((order) =>
      [order.instId, order.action, order.side, order.posSide, order.status]
        .some((value) => String(value ?? '').toLowerCase().includes(text)),
    );
  }, [query, state.orders]);

  return (
    <PageShell title="待确认订单">
      <Stack spacing={2}>
        <PageHeader
          title="待确认订单"
          subtitle="这里是 OKX 实盘提交前的人工审核台。确认前必须填写保证金并复核方向、杠杆、止损和止盈。"
          eyebrow="Order Review"
          actions={
            <>
              <TextField
                size="small"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="搜索合约、方向、状态"
                InputProps={{
                  startAdornment: (
                    <InputAdornment position="start">
                      <SearchIcon fontSize="small" />
                    </InputAdornment>
                  ),
                }}
                sx={{ minWidth: { xs: 1, sm: 240 } }}
              />
              <Button
                variant="outlined"
                size="small"
                startIcon={refreshing ? <CircularProgress size={14} /> : <RefreshIcon />}
                onClick={() => void load(true)}
                disabled={refreshing}
              >
                刷新
              </Button>
            </>
          }
        />

        <TradingStatusStrip risk={state.risk} account={state.account} pendingCount={state.orders.length} />

        <Alert severity="warning">
          实盘确认会调用 OKX 下单接口。确认前请核对合约、方向、保证金、杠杆、止损和止盈。
        </Alert>

        {error ? <Alert severity="error">{error}</Alert> : null}

        <Box sx={{ ...glassCard }}>
          {loading ? (
            <Box sx={{ minHeight: 260, display: 'grid', placeItems: 'center' }}>
              <CircularProgress size={28} />
            </Box>
          ) : filtered.length === 0 ? (
            <Box sx={{ minHeight: 220, display: 'grid', placeItems: 'center', p: 3 }}>
              <Typography color="text.secondary">当前没有匹配的待确认订单。</Typography>
            </Box>
          ) : (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>合约</TableCell>
                    <TableCell>动作</TableCell>
                    <TableCell>方向</TableCell>
                    <TableCell align="right">价格</TableCell>
                    <TableCell align="right">杠杆</TableCell>
                    <TableCell align="right">止损</TableCell>
                    <TableCell align="right">止盈</TableCell>
                    <TableCell>状态</TableCell>
                    <TableCell align="right">操作</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {filtered.map((order) => (
                    <TableRow key={String(order.id)} hover>
                      <TableCell sx={{ fontWeight: 900 }}>{String(order.instId ?? '-')}</TableCell>
                      <TableCell>{formatAction(order.action)}</TableCell>
                      <TableCell>{`${formatSide(order.side)} / ${formatSide(order.posSide)}`}</TableCell>
                      <TableCell align="right">{formatPrice(order.price)}</TableCell>
                      <TableCell align="right">{formatLeverage(order.leverage)}</TableCell>
                      <TableCell align="right">{formatPrice(order.stopLossPrice)}</TableCell>
                      <TableCell align="right">{formatPrice(order.takeProfitPrice)}</TableCell>
                      <TableCell>
                        <StatusChip value={String(order.status ?? '-')} />
                      </TableCell>
                      <TableCell align="right">
                        <PendingOrderActions record={order} onChanged={() => void load(true)} />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </Box>

        <Box sx={{ ...compactGlass, borderRadius: 1, p: 1.25 }}>
          <Typography variant="caption" color="text.secondary">
            显示 {filtered.length} / {state.orders.length} 条待确认订单。
          </Typography>
        </Box>
      </Stack>
    </PageShell>
  );
}
