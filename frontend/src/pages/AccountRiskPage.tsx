import { Alert, Box, Card, CardContent, Stack, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Loading, useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { ClosePositionDialog } from '../components/ClosePositionDialog';
import { compactGlass } from '../components/glass';
import { PageHeader, PageShell } from '../components/PageShell';
import { PositionSnapshotTable, positionKey } from '../components/PositionSnapshotTable';
import { StatusChip } from '../components/StatusChip';
import { TradeMetricCard } from '../components/TradeMetricCard';
import { TradingStatusStrip } from '../components/TradingStatusStrip';
import { formatLeverage, formatStatus, formatUSDT } from '../formatters';

export function AccountRiskPage() {
  const notify = useNotify();
  const [loading, setLoading] = useState(true);
  const [account, setAccount] = useState<Record<string, unknown>>({});
  const [risk, setRisk] = useState<Record<string, unknown>>({});
  const [positions, setPositions] = useState<Record<string, unknown>[]>([]);
  const [orders, setOrders] = useState<Record<string, unknown>[]>([]);
  const [positionError, setPositionError] = useState('');
  const [closeDialogPosition, setCloseDialogPosition] = useState<Record<string, unknown> | null>(null);
  const [closingKey, setClosingKey] = useState('');

  async function load() {
    setLoading(true);
    setPositionError('');
    try {
      const [accountData, riskData, orderData] = await Promise.all([
        quantApi.accountSummary(),
        quantApi.riskStatus(),
        quantApi.pendingOrders(),
      ]);
      setAccount(accountData as Record<string, unknown>);
      setRisk(riskData as Record<string, unknown>);
      setOrders(Array.isArray(orderData) ? (orderData as Record<string, unknown>[]) : []);
    } catch (error) {
      notify(error instanceof Error ? error.message : '加载账户与风控失败', { type: 'error' });
    }

    try {
      const positionData = await quantApi.positions();
      setPositions(Array.isArray(positionData) ? (positionData as Record<string, unknown>[]) : []);
    } catch (error) {
      setPositions([]);
      setPositionError(error instanceof Error ? error.message : '持仓接口调用失败');
    } finally {
      setLoading(false);
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
      await load();
    } catch (error) {
      notify(error instanceof Error ? error.message : '平仓提交失败', { type: 'error', multiLine: true });
    } finally {
      setClosingKey('');
    }
  }

  useEffect(() => {
    void load();
  }, []);

  if (loading) {
    return <Loading />;
  }

  const accountMode = String(account.mode ?? '-');
  const accountMessage = String(account.message ?? '');

  return (
    <PageShell title="账户与风控">
      <Stack spacing={2.5}>
        <PageHeader
          title="账户与风控"
          subtitle="集中查看 OKX 账户总览、可用余额、持仓快照和风控拦截原因。自动交易使用可用余额。"
          eyebrow="Risk Desk"
          status={<StatusChip value={accountMode} />}
          onRefresh={() => void load()}
        />

        {accountMode === 'OKX_UNBOUND' && <Alert severity="warning">尚未绑定OKX API，余额和持仓不会返回真实数据。</Alert>}
        {accountMode === 'OKX_ERROR' && (
          <Alert severity="error">OKX接口调用失败：{accountMessage || '请检查API权限、IP白名单和Passphrase。'}</Alert>
        )}
        {accountMode === 'OKX_EMPTY' && <Alert severity="info">OKX已返回成功响应，但账户余额数据为空。</Alert>}
        {positionError && <Alert severity="error">OKX持仓接口调用失败：{positionError}</Alert>}

        <TradingStatusStrip risk={risk} account={account} pendingCount={orders.length} />

        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: 'repeat(4, minmax(0, 1fr))' },
            gap: 2,
          }}
        >
          <TradeMetricCard label="账户模式" value={formatStatus(accountMode)} helper={accountMessage || undefined} />
          <TradeMetricCard label="账户总览" value={formatUSDT(account.equity)} helper="OKX账户全部资金参考" />
          <TradeMetricCard label="可用余额" value={formatUSDT(account.availableBalance)} helper="自动交易实际使用" />
          <TradeMetricCard
            label="风控"
            value={<StatusChip value={risk.passed as boolean | undefined} />}
            helper={String(risk.rejectReason ?? formatStatus(risk.riskLevel) ?? '无拦截')}
            accent={risk.passed === false ? 'error' : 'success'}
          />
        </Box>

        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: '0.85fr 1.15fr' }, gap: 2 }}>
          <Card>
            <CardContent>
              <Stack spacing={1.5}>
                <Typography variant="h6">风控解释</Typography>
                <RiskRule label="风控状态" value={<StatusChip value={risk.passed as boolean | undefined} />} />
                <RiskRule label="风险等级" value={formatStatus(risk.riskLevel)} />
                <RiskRule label="建议杠杆" value={formatLeverage(risk.adjustedLeverage)} />
                <RiskRule label="最大单笔亏损" value={formatUSDT(risk.maxLossAmount)} />
                <RiskRule label="拒绝原因" value={String(risk.rejectReason ?? '无拦截')} />
              </Stack>
            </CardContent>
          </Card>

          <Card>
            <CardContent>
              <Stack spacing={1.5}>
                <Typography variant="h6">持仓快照</Typography>
                {positionError ? (
                  <Alert severity="error">持仓不可用，不代表账户空仓：{positionError}</Alert>
                ) : (
                  <PositionSnapshotTable
                    positions={positions}
                    closingKey={closingKey}
                    onClosePosition={setCloseDialogPosition}
                  />
                )}
              </Stack>
            </CardContent>
          </Card>
        </Box>
      </Stack>
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

function RiskRule({ label, value }: { label: string; value: ReactNode }) {
  return (
    <Stack
      direction="row"
      justifyContent="space-between"
      alignItems="center"
      gap={2}
      sx={{ ...compactGlass, borderRadius: 1, p: 1.15 }}
    >
      <Typography color="text.secondary" variant="body2">
        {label}
      </Typography>
      <Typography component="div" fontWeight={800} sx={{ fontFamily: '"JetBrains Mono", monospace', textAlign: 'right' }}>
        {value}
      </Typography>
    </Stack>
  );
}
