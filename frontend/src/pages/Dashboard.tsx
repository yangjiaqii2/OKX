import { Alert, Box, Card, CardContent, Chip, CircularProgress, Stack, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
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
  const notify = useNotify();
  const [loading, setLoading] = useState(true);
  const [assetCurrency, setAssetCurrency] = useState<'USD' | 'RMB'>('USD');
  const [state, setState] = useState<DashboardState>({
    contracts: [],
    orders: [],
    positions: [],
  });

  async function load() {
    setLoading(true);
    try {
      const [riskResult, accountResult, profitResult, contractResult, orderResult, fxRateResult] = await Promise.allSettled([
        quantApi.riskStatus(),
        quantApi.accountSummary(),
        quantApi.autoTradeProfitSummary(),
        quantApi.contractCandidates(),
        quantApi.pendingOrders(),
        quantApi.fxRate('USD', 'CNY'),
      ]);
      let positions: Record<string, unknown>[] = [];
      let positionsError = '';
      try {
        const positionData = await quantApi.positions();
        positions = Array.isArray(positionData) ? (positionData as Record<string, unknown>[]) : [];
      } catch (error) {
        positionsError = error instanceof Error ? error.message : '持仓接口调用失败';
      }
      const contractsError = contractResult.status === 'rejected'
        ? (contractResult.reason instanceof Error ? contractResult.reason.message : '合约机会池加载失败')
        : '';
      const ordersError = orderResult.status === 'rejected'
        ? (orderResult.reason instanceof Error ? orderResult.reason.message : '待确认订单加载失败')
        : '';
      const profitError = profitResult.status === 'rejected'
        ? (profitResult.reason instanceof Error ? profitResult.reason.message : '自动交易收益加载失败')
        : '';
      const fxRateError = fxRateResult.status === 'rejected'
        ? (fxRateResult.reason instanceof Error ? fxRateResult.reason.message : '汇率加载失败')
        : '';
      if (fxRateError) {
        setAssetCurrency('USD');
      }
      setState({
        risk: riskResult.status === 'fulfilled' ? riskResult.value as Record<string, unknown> : {},
        account: accountResult.status === 'fulfilled' ? accountResult.value as Record<string, unknown> : {},
        profit: profitResult.status === 'fulfilled' ? profitResult.value as Record<string, unknown> : {},
        fxRate: fxRateResult.status === 'fulfilled' ? fxRateResult.value as Record<string, unknown> : undefined,
        contracts: contractResult.status === 'fulfilled' && Array.isArray(contractResult.value) ? contractResult.value : [],
        orders: orderResult.status === 'fulfilled' && Array.isArray(orderResult.value) ? orderResult.value : [],
        positions,
        positionsError,
        contractsError,
        ordersError,
        profitError,
        fxRateError,
      });
      if (riskResult.status === 'rejected' || accountResult.status === 'rejected') {
        notify('核心账户或风控数据加载失败，请检查后端服务。', { type: 'warning' });
      }
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
      <PageShell title="量化交易终端">
        <Stack spacing={2}>
          <PageHeader title="量化交易终端" subtitle="正在同步 OKX 账户、风险状态、候选合约和待确认订单。" />
          <Box sx={{ ...glassCard, py: 8, display: 'grid', placeItems: 'center', borderRadius: 1 }}>
            <CircularProgress size={28} />
          </Box>
        </Stack>
      </PageShell>
    );
  }

  const accountMode = String(state.account?.mode ?? 'OKX_UNBOUND');
  const pendingCount = state.orders.length;
  const topContracts = state.contracts.slice(0, 4) as Record<string, unknown>[];
  const fxRateAvailable = Number.isFinite(Number(state.fxRate?.rate)) && Number(state.fxRate?.rate) > 0;
  const formatAsset = (value: unknown) => formatAssetMoney(value, assetCurrency, state.fxRate);
  const profitQuality = profitQualityLabel(String(state.profit?.dataQuality ?? 'ESTIMATED_UNREALIZED_ONLY'));
  
  return (
    <PageShell title="量化交易终端">
      <Stack spacing={3}>
        <PageHeader
          title="量化交易终端"
          subtitle="OKX合约信号进入待确认流程后执行，账户与风控状态优先。"
          status={<StatusChip value={accountMode} />}
          onRefresh={() => void load()}
          actions={
            <ToggleButtonGroup
              size="small"
              exclusive
              value={assetCurrency}
              onChange={(_, value) => {
                if (!value) return;
                if (value === 'RMB' && !fxRateAvailable) {
                  setAssetCurrency('USD');
                  notify('RMB汇率不可用，已回退USD显示。', { type: 'warning' });
                  return;
                }
                setAssetCurrency(value);
              }}
            >
              <ToggleButton value="USD">USD</ToggleButton>
              <ToggleButton value="RMB" disabled={!fxRateAvailable}>RMB</ToggleButton>
            </ToggleButtonGroup>
          }
        />

        {/* 告警 */}
        {accountMode === 'OKX_ERROR' && (
          <Alert severity="error" sx={{ fontWeight: 600 }}>
            OKX账户接口异常：{String(state.account?.message ?? '')}
          </Alert>
        )}
        {state.contractsError ? <Alert severity="warning">OKX合约机会池暂不可用：{state.contractsError}</Alert> : null}
        {state.ordersError ? <Alert severity="warning">待确认订单暂不可用：{state.ordersError}</Alert> : null}
        {state.profitError ? <Alert severity="warning">自动交易收益暂不可用：{state.profitError}</Alert> : null}
        {state.fxRateError ? <Alert severity="warning">RMB汇率不可用，已回退USD：{state.fxRateError}</Alert> : null}
        {assetCurrency === 'RMB' && fxRateAvailable ? (
          <Alert severity="info">
            RMB按 {String(state.fxRate?.source ?? 'CONFIGURED')} 汇率 USD/CNY={formatNumber(state.fxRate?.rate, 4)}
            {state.fxRate?.updatedAt ? `，更新时间 ${String(state.fxRate.updatedAt)}` : ''}
          </Alert>
        ) : null}

        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(0, 1fr))', lg: 'repeat(6, minmax(0, 1fr))' },
            gap: 1.5,
          }}
        >
          <TradeMetricCard
            label="账户总权益"
            value={formatAsset(state.account?.equity)}
            helper={formatStatus(accountMode)}
            accent={accountMode === 'OKX_REAL' ? 'success' : 'warning'}
          />
          <TradeMetricCard
            label="可用余额"
            value={formatAsset(state.account?.availableBalance)}
            helper="OKX账户数据源"
          />
          <TradeMetricCard
            label="今日已实现 PnL"
            value={formatAsset(state.profit?.todayRealizedPnlUsdt)}
            helper={profitQuality}
            accent={state.profit?.todayRealizedPnlUsdt == null ? 'warning' : Number(state.profit.todayRealizedPnlUsdt) >= 0 ? 'success' : 'error'}
          />
          <TradeMetricCard
            label="未实现 PnL"
            value={formatAsset(state.profit?.unrealizedPnlUsdt)}
            helper={profitQuality}
            accent={Number(state.profit?.unrealizedPnlUsdt ?? 0) >= 0 ? 'success' : 'error'}
          />
          <TradeMetricCard
            label="累计已实现 PnL"
            value={formatAsset(state.profit?.realizedPnlUsdt)}
            helper={profitQuality.includes('真实') ? 'OKX成交净收益' : '估算口径'}
            accent={state.profit?.realizedPnlUsdt == null ? 'warning' : Number(state.profit.realizedPnlUsdt) >= 0 ? 'success' : 'error'}
          />
          <TradeMetricCard
            label="当前持仓"
            value={state.positions.length}
            helper="OKX仓位快照"
            accent={state.positions.length > 0 ? 'warning' : 'info'}
          />
        </Box>

        {state.profit?.message ? (
          <Alert severity="info">
            {String(state.profit.message)}
          </Alert>
        ) : null}

        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(0, 1fr))' }, gap: 1.5 }}>
          <TradeMetricCard label="已实现毛收益" value={formatAsset(state.profit?.grossRealizedPnlUsdt)} helper={profitQuality} />
          <TradeMetricCard label="交易手续费" value={formatAsset(state.profit?.tradingFeesUsdt)} helper="来自可追溯 OKX fills" />
          <TradeMetricCard label="资金费" value={formatAsset(state.profit?.fundingUsdt)} helper="来自已管理合约的 OKX bills" />
        </Box>

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
                label="自动交易净 PnL"
                value={formatAsset(state.profit?.totalNetPnlUsdt)}
                helper={profitQuality}
                accent={state.profit?.totalNetPnlUsdt == null ? 'warning' : Number(state.profit.totalNetPnlUsdt) >= 0 ? 'success' : 'error'}
              />
              <TradeMetricCard
                label="预算收益率"
                value={formatPercent(state.profit?.budgetRoiPct)}
                helper={`已提交 ${formatAsset(state.profit?.submittedMarginUsdt)}`}
              />
              <TradeMetricCard
                label="合约机会"
                value={state.contracts.length}
                helper="按成交量与趋势筛选"
                accent="info"
              />
              <TradeMetricCard
                label="待确认订单"
                value={pendingCount}
                helper="确认后按当前交易模式提交OKX"
                accent={pendingCount > 0 ? 'warning' : 'success'}
              />
            </Box>

            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', xl: '1.2fr 0.8fr' }, gap: 2 }}>
              <Card>
                <CardContent>
                  <Stack spacing={1.5}>
                    <Stack direction="row" justifyContent="space-between" alignItems="center">
                      <Typography variant="h6">持仓与PnL</Typography>
                      <StatusChip value={accountMode} />
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
                            borderRadius: 1,
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
                        borderRadius: 1,
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

function formatAssetMoney(value: unknown, currency: 'USD' | 'RMB', fxRate?: Record<string, unknown>) {
  if (value == null || value === '') {
    return '-';
  }
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '-';
  }
  if (currency === 'RMB') {
    const rate = Number(fxRate?.rate);
    if (Number.isFinite(rate) && rate > 0) {
      return `¥${formatNumber(number * rate, 2)}`;
    }
  }
  return formatUSDT(number);
}

function profitQualityLabel(value: string) {
  switch (value) {
    case 'OKX_FILLS_NET_PNL':
      return '真实净收益';
    case 'EXCHANGE_RECENT_LEDGER':
      return 'OKX近期账本：可追溯订单';
    case 'CLOSE_RECORD_ESTIMATED':
      return '估算：平仓记录口径';
    case 'PARTIAL_CLOSE_RECORD_ESTIMATED':
      return '估算：部分平仓已对账';
    case 'REALIZED_PNL_UNAVAILABLE':
      return '不可用：未接入成交账单';
    case 'POSITION_SNAPSHOT_UNAVAILABLE':
      return '不可用：持仓快照失败';
    case 'ESTIMATED_UNREALIZED_ONLY':
      return '估算：仅当前浮盈';
    default:
      return '估算收益';
  }
}
