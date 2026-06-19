import RefreshIcon from '@mui/icons-material/Refresh';
import { Alert, Box, Button, CircularProgress, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { glassCard } from '../components/glass';
import { PageHeader, PageShell } from '../components/PageShell';
import { StatusChip } from '../components/StatusChip';
import { formatPrice, formatSide, formatUSDT } from '../formatters';

type LifecycleRow = Record<string, unknown>;

export function AutoTradeLifecycleList() {
  const notify = useNotify();
  const [rows, setRows] = useState<LifecycleRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  async function load(silent = false) {
    silent ? setRefreshing(true) : setLoading(true);
    try {
      const result = await quantApi.autoTradeLifecycle();
      setRows(Array.isArray(result) ? result as LifecycleRow[] : []);
    } catch (error) {
      notify(error instanceof Error ? error.message : '生命周期加载失败', { type: 'error' });
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  return (
    <PageShell title="自动交易生命周期">
      <Stack spacing={2}>
        <PageHeader
          title="自动交易生命周期"
          subtitle={`自动交易仓位 ${rows.length} 条`}
          eyebrow="Lifecycle"
          actions={
            <Button variant="outlined" size="small" startIcon={refreshing ? <CircularProgress size={14} /> : <RefreshIcon />} onClick={() => void load(true)} disabled={refreshing}>
              刷新
            </Button>
          }
        />
        <Alert severity="info">
          预算占用来自入场委托或实际入场仓位；止损、TP1、TP2、TP3 是 reduceOnly 保护单，不计入新的开仓预算。
        </Alert>
        <Box sx={{ ...glassCard }}>
          {loading ? (
            <Box sx={{ minHeight: 220, display: 'grid', placeItems: 'center' }}>
              <CircularProgress size={28} />
            </Box>
          ) : rows.length === 0 ? (
            <Box sx={{ minHeight: 180, display: 'grid', placeItems: 'center', p: 3 }}>
              <Typography color="text.secondary">暂无记录。</Typography>
            </Box>
          ) : (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>合约</TableCell>
                    <TableCell>方向</TableCell>
                    <TableCell>生命周期</TableCell>
                    <TableCell>入场状态</TableCell>
                    <TableCell>保护单状态</TableCell>
                    <TableCell align="right">成交数量</TableCell>
                    <TableCell>保护单</TableCell>
                    <TableCell>当前持仓</TableCell>
                    <TableCell>平仓</TableCell>
                    <TableCell>预算</TableCell>
                    <TableCell>最近事件</TableCell>
                    <TableCell>人工处理</TableCell>
                    <TableCell align="right">持仓时长</TableCell>
                    <TableCell align="right">入场价</TableCell>
                    <TableCell align="right">均价</TableCell>
                    <TableCell align="right">浮盈亏</TableCell>
                    <TableCell align="right">浮盈亏%</TableCell>
                    <TableCell align="right">预算占用</TableCell>
                    <TableCell>下一步</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {rows.map((row, index) => (
                    <TableRow key={`${String(row.instId)}-${index}`} hover>
                      <TableCell sx={{ fontWeight: 900 }}>{String(row.instId ?? '-')}</TableCell>
                      <TableCell>{formatSide(row.posSide)}</TableCell>
                      <TableCell><StatusChip value={String(row.lifecycleStatus ?? '-')} /></TableCell>
                      <TableCell>{String(row.entryOrderStatus ?? '-')}</TableCell>
                      <TableCell>{String(row.protectionOrderStatus ?? '-')}</TableCell>
                      <TableCell align="right">{formatPrice((row.entryOrder as Record<string, unknown> | undefined)?.filledSize)}</TableCell>
                      <TableCell>{formatProtectionSummary(row.protectionOrders)}</TableCell>
                      <TableCell>{formatPositionFact(row.positionLifecycle)}</TableCell>
                      <TableCell>{formatCloseFact(row.closePosition)}</TableCell>
                      <TableCell>{formatBudgetFact(row.budget)}</TableCell>
                      <TableCell sx={{ maxWidth: 220, overflowWrap: 'anywhere' }}>{formatRecentEvent(row.recentEvents)}</TableCell>
                      <TableCell>{row.manualAttentionRequired ? <StatusChip value="MANUAL_ATTENTION_REQUIRED" /> : '-'}</TableCell>
                      <TableCell align="right">{formatDuration(row.holdDuration)}</TableCell>
                      <TableCell align="right">{formatPrice(row.entryPrice)}</TableCell>
                      <TableCell align="right">{formatPrice(row.avgPx)}</TableCell>
                      <TableCell align="right">{formatUSDT(row.unrealizedPnl)}</TableCell>
                      <TableCell align="right">{formatPct(row.unrealizedPnlPct)}</TableCell>
                      <TableCell align="right">{formatUSDT(row.budgetUsed)}</TableCell>
                      <TableCell sx={{ maxWidth: 260, overflowWrap: 'anywhere' }}>{String(row.nextAction ?? '-')}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </Box>
      </Stack>
    </PageShell>
  );
}

function formatDuration(value: unknown) {
  const raw = String(value ?? '');
  const match = raw.match(/PT(?:(\d+)H)?(?:(\d+)M)?/);
  if (!match) return '-';
  const hours = Number(match[1] ?? 0);
  const minutes = Number(match[2] ?? 0);
  return `${hours}h ${minutes}m`;
}

function formatPct(value: unknown) {
  const number = Number(value);
  if (!Number.isFinite(number)) return '-';
  return `${number.toFixed(2)}%`;
}

function formatProtectionSummary(value: unknown) {
  if (!Array.isArray(value) || value.length === 0) return '-';
  const statuses = value
    .map((item) => String((item as Record<string, unknown>).status ?? '-'))
    .slice(0, 3)
    .join('/');
  return `${value.length}笔 ${statuses}`;
}

function formatPositionFact(value: unknown) {
  const fact = value as Record<string, unknown> | undefined;
  if (!fact) return '-';
  if (fact.positionSyncAvailable === false) return '持仓同步不可用';
  if (!fact.open) return '无持仓';
  return `${formatPrice(fact.size)} @ ${formatPrice(fact.avgPx)}`;
}

function formatCloseFact(value: unknown) {
  const fact = value as Record<string, unknown> | undefined;
  if (!fact) return '-';
  return `${String(fact.status ?? '-')} ${formatUSDT(fact.realizedPnl)}`;
}

function formatBudgetFact(value: unknown) {
  const fact = value as Record<string, unknown> | undefined;
  if (!fact) return '-';
  return `${String(fact.status ?? '-')} ${formatUSDT(fact.amount)}`;
}

function formatRecentEvent(value: unknown) {
  if (!Array.isArray(value) || value.length === 0) return '-';
  const event = value[0] as Record<string, unknown>;
  return `${String(event.eventType ?? '-')} ${String(event.reasonCode ?? '')}`.trim();
}
