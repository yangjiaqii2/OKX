import { PanelState } from '../components/PanelState';
import { ScrollableTable } from '../components/ScrollableTable';
import CancelIcon from '@mui/icons-material/Cancel';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import RefreshIcon from '@mui/icons-material/Refresh';
import SearchIcon from '@mui/icons-material/Search';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  InputAdornment,
  Stack,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { Confirm, useNotify } from 'react-admin';
import { useEffect, useMemo, useState } from 'react';
import { quantApi } from '../api/quantApi';
import { insetPanel, panel } from '../components/surfaces';
import { PageHeader, PageShell } from '../components/PageShell';
import { PendingOrderReviewDialog } from '../components/PendingOrderReviewDialog';
import { StatusChip } from '../components/StatusChip';
import { TradingStatusStrip } from '../components/TradingStatusStrip';
import { directionTone, formatAction, formatAutoTradeReason, formatLeverage, formatPrice, formatSide } from '../formatters';
import { t, useLanguage } from '../i18n';

type PendingOrderState = {
  risk?: Record<string, unknown>;
  account?: Record<string, unknown>;
  orders: Record<string, unknown>[];
  tradingMode: string;
};

const emptyState: PendingOrderState = {
  orders: [],
  tradingMode: 'DISABLED',
};

const PendingOrderActions = ({
  record,
  onChanged,
  tradingMode,
}: {
  record: Record<string, unknown>;
  onChanged: () => void;
  tradingMode: string;
}) => {
  useLanguage();
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
        throw new Error(String(result.message ?? t("订单未提交OKX")));
      }
      notify(String(result.message ?? t("OKX委托已提交，等待成交，请到OKX当前委托/持仓确认")), { type: 'info' });
      onChanged();
    } catch (error) {
      notify(error instanceof Error ? formatAutoTradeReason(error.message) : t("确认订单失败"), { type: 'error' });
    } finally {
      setSubmitting(false);
      setConfirmOpen(false);
    }
  }

  async function cancelOrder() {
    setSubmitting(true);
    try {
      await quantApi.cancelOrder(id);
      notify(t("订单已取消"), { type: 'success' });
      onChanged();
    } catch (error) {
      notify(error instanceof Error ? error.message : t("取消订单失败"), { type: 'error' });
    } finally {
      setSubmitting(false);
      setCancelOpen(false);
    }
  }

  return (
    <Stack direction="row" gap={1} justifyContent="flex-end">
      <Button
        size="small"
        variant="contained"
        color={tradingMode === 'LIVE' ? 'error' : 'info'}
        startIcon={<CheckCircleIcon />}
        onClick={() => setConfirmOpen(true)}
        disabled={tradingMode === 'DISABLED'}
      >
        {tradingMode === 'LIVE' ? t("LIVE 确认") : tradingMode === 'DEMO' ? t("DEMO 确认") : t("交易已关闭")}
      </Button>
      <Button size="small" variant="outlined" color="warning" startIcon={<CancelIcon />} onClick={() => setCancelOpen(true)}>
        {t("取消")}
      </Button>
      <PendingOrderReviewDialog
        open={confirmOpen}
        record={record}
        submitting={submitting}
        tradingMode={tradingMode}
        onConfirm={(marginAmount) => void confirmOrder(marginAmount)}
        onClose={() => setConfirmOpen(false)}
      />
      <Confirm
        isOpen={cancelOpen}
        title={t("取消待确认订单")}
        content={t('取消 {instrument} 的待确认订单。', { instrument: String(record.instId) })}
        confirm={t("取消订单")}
        cancel={t("返回")}
        onConfirm={() => void cancelOrder()}
        onClose={() => setCancelOpen(false)}
      />
    </Stack>
  );
};

export function PendingOrderList() {
  useLanguage();
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
      const [risk, account, orders, runtime] = await Promise.all([
        quantApi.riskStatus(),
        quantApi.accountSummary(),
        quantApi.pendingOrders(),
        quantApi.runtimeStatus(),
      ]);
      setState({
        risk: risk as Record<string, unknown>,
        account: account as Record<string, unknown>,
        orders: Array.isArray(orders) ? (orders as Record<string, unknown>[]) : [],
        tradingMode: String((runtime as Record<string, unknown>).tradingMode ?? 'DISABLED'),
      });
      setError('');
    } catch (loadError) {
      const message = loadError instanceof Error ? loadError.message : t("待确认订单加载失败");
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
    <PageShell title={t("待确认订单")}>
      <Stack spacing={2}>
        <PageHeader
          title={t("待确认订单")}
          subtitle={t("这里是 OKX 订单提交前的人工审核台。确认前必须填写保证金并复核方向、杠杆、止损和止盈。")}
          filters={
            <>
              <TextField
                size="small"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder={t("搜索合约、方向、状态")}
                inputProps={{ 'aria-label': t("搜索合约、方向、状态") }}
                InputProps={{
                  startAdornment: (
                    <InputAdornment position="start">
                      <SearchIcon fontSize="small" />
                    </InputAdornment>
                  ),
                }}
                sx={{ width: { xs: 1, sm: 240 } }}
              />
              <Button
                variant="outlined"
                size="small"
                startIcon={refreshing ? <CircularProgress size={14} /> : <RefreshIcon />}
                onClick={() => void load(true)}
                disabled={refreshing}
              >
                {t("刷新")}
              </Button>
            </>
          }
        />

        <TradingStatusStrip risk={state.risk} account={state.account} pendingCount={state.orders.length} />

        <Alert severity={state.tradingMode === 'LIVE' ? 'error' : state.tradingMode === 'DEMO' ? 'info' : 'warning'}>
          {t("当前模式：")}{state.tradingMode}。{state.tradingMode === 'LIVE' ? t("确认会操作真实资金。") : state.tradingMode === 'DEMO' ? t("确认只提交 OKX 模拟交易。") : t("交易写请求已关闭。")}
        </Alert>

        {error ? <Alert severity="error">{error}</Alert> : null}

        <Box sx={{ ...panel }}>
          {loading ? (
            <PanelState loading />
          ) : filtered.length === 0 ? (
            <PanelState>{t("当前没有匹配的待确认订单。")}</PanelState>
          ) : (
            <ScrollableTable label={t("待确认订单")}>
              <TableHead>
                <TableRow>
                  <TableCell>{t("合约")}</TableCell>
                  <TableCell>{t("动作")}</TableCell>
                  <TableCell>{t("方向")}</TableCell>
                  <TableCell align="right">{t("价格")}</TableCell>
                  <TableCell align="right">{t("杠杆")}</TableCell>
                  <TableCell align="right">{t("止损")}</TableCell>
                  <TableCell align="right">{t("止盈")}</TableCell>
                  <TableCell>{t("状态")}</TableCell>
                  <TableCell align="right">{t("操作")}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {filtered.map((order) => (
                  <TableRow key={String(order.id)} hover>
                    <TableCell sx={{ fontWeight: 900 }}>{String(order.instId ?? '-')}</TableCell>
                    <TableCell>{formatAction(order.action)}</TableCell>
                    <TableCell>
                      <Chip size="small" color={directionTone(order.side)} label={formatSide(order.side) + ' / ' + formatSide(order.posSide)} />
                    </TableCell>
                    <TableCell align="right">{formatPrice(order.price)}</TableCell>
                    <TableCell align="right">{formatLeverage(order.leverage)}</TableCell>
                    <TableCell align="right">{formatPrice(order.stopLossPrice)}</TableCell>
                    <TableCell align="right">{formatPrice(order.takeProfitPrice)}</TableCell>
                    <TableCell>
                      <StatusChip value={String(order.status ?? '-')} />
                    </TableCell>
                    <TableCell align="right">
                      <PendingOrderActions record={order} tradingMode={state.tradingMode} onChanged={() => void load(true)} />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </ScrollableTable>
          )}
        </Box>

        <Box sx={{ ...insetPanel, borderRadius: 1, p: 1.5 }}>
          <Typography variant="caption" color="text.secondary">
            {t("显示")} {filtered.length} / {state.orders.length} {t("条待确认订单。")}
          </Typography>
        </Box>
      </Stack>
    </PageShell>
  );
}
