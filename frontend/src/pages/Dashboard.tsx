import { Alert, Box, Card, CardContent, Chip, CircularProgress, Stack, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { compactGlass, glassCard } from '../components/glass';
import { PageHeader, PageShell } from '../components/PageShell';
import { PositionSnapshotTable } from '../components/PositionSnapshotTable';
import { RiskCommandPanel } from '../components/RiskCommandPanel';
import { StatusChip } from '../components/StatusChip';
import { TradeMetricCard } from '../components/TradeMetricCard';
import { formatLeverage, formatNumber, formatPercent, formatPrice, formatStatus, formatUSDT } from '../formatters';

type DashboardState = {
  risk?: Record<string, unknown>;
  account?: Record<string, unknown>;
  contracts: unknown[];
  orders: unknown[];
  positions: Record<string, unknown>[];
  positionsError?: string;
};

export function Dashboard() {
  const notify = useNotify();
  const [loading, setLoading] = useState(true);
  const [state, setState] = useState<DashboardState>({
    contracts: [],
    orders: [],
    positions: [],
  });

  async function load() {
    setLoading(true);
    try {
      const [risk, account, contracts, orders] = await Promise.all([
        quantApi.riskStatus(),
        quantApi.accountSummary(),
        quantApi.contractCandidates(),
        quantApi.pendingOrders(),
      ]);
      let positions: Record<string, unknown>[] = [];
      let positionsError = '';
      try {
        const positionData = await quantApi.positions();
        positions = Array.isArray(positionData) ? (positionData as Record<string, unknown>[]) : [];
      } catch (error) {
        positionsError = error instanceof Error ? error.message : '持仓接口调用失败';
      }
      setState({
        risk: risk as Record<string, unknown>,
        account: account as Record<string, unknown>,
        contracts: Array.isArray(contracts) ? contracts : [],
        orders: Array.isArray(orders) ? orders : [],
        positions,
        positionsError,
      });
    } catch (error) {
      notify(error instanceof Error ? error.message : '加载总览失败', { type: 'error' });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  if (loading) {
    return (
      <PageShell title="量化实盘控制台">
        <Stack spacing={2}>
          <PageHeader title="量化实盘控制台" subtitle="正在同步 OKX 账户、风险状态、候选合约和待确认订单。" />
          <Box sx={{ ...glassCard, py: 8, display: 'grid', placeItems: 'center', borderRadius: 3 }}>
            <CircularProgress size={28} />
          </Box>
        </Stack>
      </PageShell>
    );
  }

  const accountMode = String(state.account?.mode ?? 'OKX_UNBOUND');
  const pendingCount = state.orders.length;
  const topContracts = state.contracts.slice(0, 4) as Record<string, unknown>[];
  
  return (
    <PageShell title="量化实盘控制台">
      <Stack spacing={3}>
        <PageHeader
          title="量化实盘控制台"
          subtitle="OKX合约人工确认后实盘，风控优先，A股仅分析筛选。"
          status={<StatusChip value={accountMode} />}
          onRefresh={() => void load()}
        />

        {/* 告警 */}
        {accountMode === 'OKX_ERROR' && (
          <Alert severity="error" sx={{ fontWeight: 600 }}>
            OKX账户接口异常：{String(state.account?.message ?? '')}
          </Alert>
        )}

        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: {
              xs: '1fr',
              lg: '320px minmax(0, 1fr)',
            },
            gap: 2,
            alignItems: 'stretch',
          }}
        >
          <RiskCommandPanel risk={state.risk} account={state.account} pendingCount={pendingCount} />

          <Stack spacing={2} minWidth={0}>
            <Box
              sx={{
                display: 'grid',
                gridTemplateColumns: { xs: '1fr', md: 'repeat(4, minmax(0, 1fr))' },
                gap: 2,
              }}
            >
              <TradeMetricCard
                label="账户权益"
                value={formatUSDT(state.account?.equity)}
                helper={formatStatus(accountMode)}
                accent={accountMode === 'OKX_REAL' ? 'success' : 'warning'}
              />
              <TradeMetricCard label="可用余额" value={formatUSDT(state.account?.availableBalance)} helper="OKX账户可用权益" />
              <TradeMetricCard
                label="合约机会"
                value={state.contracts.length}
                helper="按成交量与趋势筛选"
                accent="info"
              />
              <TradeMetricCard
                label="待确认订单"
                value={pendingCount}
                helper="确认后提交OKX实盘"
                accent={pendingCount > 0 ? 'warning' : 'success'}
              />
            </Box>

            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', xl: '1.2fr 0.8fr' }, gap: 2 }}>
              <Card>
                <CardContent>
                  <Stack spacing={1.5}>
                    <Stack direction="row" justifyContent="space-between" alignItems="center">
                      <Typography variant="h6">持仓与PnL</Typography>
                      <StatusChip value={state.positions.length > 0 ? 'OKX_REAL' : accountMode} />
                    </Stack>
                    {state.positionsError ? (
                      <Alert severity="error">持仓接口调用失败：{state.positionsError}</Alert>
                    ) : (
                      <PositionSnapshotTable positions={state.positions} />
                    )}
                  </Stack>
                </CardContent>
              </Card>

              <Card>
                <CardContent>
                  <Stack spacing={1.5}>
                    <Typography variant="h6">待处理动作</Typography>
                    {pendingCount === 0 ? (
                      <Typography color="text.secondary" variant="body2">
                        当前没有待确认订单。
                      </Typography>
                    ) : (
                      (state.orders as Record<string, unknown>[]).slice(0, 5).map((order) => (
                        <Box
                          key={String(order.id)}
                          sx={{
                            ...compactGlass,
                            p: 1.25,
                            borderLeft: '3px solid',
                            borderColor: 'warning.main',
                            borderRadius: 2,
                            bgcolor: 'rgba(245, 158, 11, 0.08)',
                          }}
                        >
                          <Typography fontWeight={800}>{String(order.instId ?? '-')}</Typography>
                          <Typography variant="caption" color="text.secondary">
                            {formatStatus(order.status)} · {String(order.leverage ?? '-')}x · 止损 {formatPrice(order.stopLossPrice)}
                          </Typography>
                        </Box>
                      ))
                    )}
                  </Stack>
                </CardContent>
              </Card>
            </Box>
          </Stack>
        </Box>

        <Card>
          <CardContent>
            <Stack spacing={1.5}>
              <Stack direction="row" alignItems="center" justifyContent="space-between">
                <Typography variant="h6">OKX机会池</Typography>
                <Chip size="small" label={`合约候选 ${state.contracts.length}`} variant="outlined" />
              </Stack>
              <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(4, minmax(0, 1fr))' }, gap: 1 }}>
                {topContracts.length === 0 ? (
                  <Typography color="text.secondary" variant="body2">
                    暂无合约机会。可以在 OKX合约 页面刷新扫描。
                  </Typography>
                ) : (
                  topContracts.map((contract) => (
                    <Box
                      key={String(contract.instId)}
                      sx={{
                        ...compactGlass,
                        p: 1.25,
                        borderRadius: 2,
                        bgcolor: 'rgba(15, 23, 42, 0.62)',
                      }}
                    >
                      <Typography fontWeight={900}>{String(contract.instId ?? '-')}</Typography>
                      <Typography sx={{ fontFamily: '"JetBrains Mono", monospace', mt: 0.5 }}>
                        {formatPrice(contract.lastPrice)}
                      </Typography>
                      <Typography
                        variant="caption"
                        sx={{
                          color: Number(contract.changePercent24h) >= 0 ? 'success.main' : 'error.main',
                          fontFamily: '"JetBrains Mono", monospace',
                        }}
                      >
                        {formatPercent(contract.changePercent24h)} · 评分 {formatNumber(contract.score, 0)} · {formatLeverage(contract.suggestedLeverage)}
                      </Typography>
                    </Box>
                  ))
                )}
              </Box>
            </Stack>
          </CardContent>
        </Card>
      </Stack>
    </PageShell>
  );
}
