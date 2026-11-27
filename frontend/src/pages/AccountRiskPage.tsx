import { useEffect, useState, type ReactNode } from 'react';
import { useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { ClosePositionDialog } from '../components/ClosePositionDialog';
import { PageHeader, PageShell } from '../components/PageShell';
import { PanelState } from '../components/PanelState';
import { PositionSnapshotTable, positionKey } from '../components/PositionSnapshotTable';
import { StatusChip } from '../components/StatusChip';
import { TradingStatusStrip } from '../components/TradingStatusStrip';
import { Alert } from '../components/ui/alert';
import { hasAccountBalance } from '../displayData';
import { formatLeverage, formatStatus, formatUSDT } from '../formatters';
import { t, useLanguage } from '../i18n';

export function AccountRiskPage() {
  useLanguage();
  const notify = useNotify();
  const [loading, setLoading] = useState(true);
  const [account, setAccount] = useState<Record<string, unknown>>({});
  const [risk, setRisk] = useState<Record<string, unknown>>({});
  const [positions, setPositions] = useState<Record<string, unknown>[]>([]);
  const [pendingCount, setPendingCount] = useState<number | null>(null);
  const [positionError, setPositionError] = useState('');
  const [closeDialogPosition, setCloseDialogPosition] = useState<Record<string, unknown> | null>(null);
  const [closingKey, setClosingKey] = useState('');

  async function load() {
    setLoading(true);
    setPositionError('');
    const [accountResult, riskResult, orderResult, positionResult] = await Promise.allSettled([
      quantApi.accountSummary(),
      quantApi.riskStatus(),
      quantApi.pendingOrders(),
      quantApi.positions(),
    ]);
    setAccount(accountResult.status === 'fulfilled' ? (accountResult.value as Record<string, unknown> | null) ?? {} : {});
    setRisk(riskResult.status === 'fulfilled' ? (riskResult.value as Record<string, unknown> | null) ?? {} : {});
    setPendingCount(orderResult.status === 'fulfilled' && Array.isArray(orderResult.value) ? orderResult.value.length : null);
    setPositions(positionResult.status === 'fulfilled' && Array.isArray(positionResult.value) ? positionResult.value : []);
    if (positionResult.status === 'rejected') {
      const error = positionResult.reason;
      setPositionError(error instanceof Error ? error.message : t('持仓接口调用失败'));
    }
    const failed = [accountResult, riskResult, orderResult].find((result) => result.status === 'rejected');
    if (failed && failed.status === 'rejected') {
      const error = failed.reason;
      notify(error instanceof Error ? error.message : t('加载账户与风控失败'), { type: 'error' });
    }
    setLoading(false);
  }

  async function confirmClosePosition() {
    if (!closeDialogPosition) return;
    setClosingKey(positionKey(closeDialogPosition));
    try {
      await quantApi.closePosition({
        instId: String(closeDialogPosition.instId ?? ''),
        posSide: String(closeDialogPosition.posSide ?? ''),
        marginMode: String(closeDialogPosition.marginMode ?? ''),
      });
      notify(`${t('已提交平仓：')}${String(closeDialogPosition.instId ?? '-')}`, { type: 'success' });
      setCloseDialogPosition(null);
      await load();
    } catch (error) {
      notify(error instanceof Error ? error.message : t('平仓提交失败'), { type: 'error', multiLine: true });
    } finally {
      setClosingKey('');
    }
  }

  useEffect(() => { void load(); }, []);

  if (loading) return <PageShell title={t('账户与风控')}><PanelState loading /></PageShell>;

  const accountMode = String(account.mode ?? '-');
  const accountMessage = String(account.message ?? '');
  const balanceAvailable = hasAccountBalance(accountMode);
  const availableBalance = account.availableBalance;

  return (
    <PageShell title={t('账户与风控')}>
      <PageHeader
        title={t('账户与风控')}
        subtitle={t('集中查看 OKX 账户总览、可用余额、持仓快照和风控拦截原因。自动交易使用可用余额。')}
        status={<StatusChip value={accountMode} />}
        onRefresh={() => void load()}
      />
      <div className="mt-4 space-y-4">
        {accountMode === 'OKX_UNBOUND' ? <Alert severity="warning">{t('尚未绑定OKX API，余额和持仓不会返回真实数据。')}</Alert> : null}
        {accountMode === 'OKX_ERROR' ? <Alert severity="error">{t('OKX接口调用失败：')}{accountMessage || t('请检查API权限、IP白名单和Passphrase。')}</Alert> : null}
        {accountMode === 'OKX_EMPTY' ? <Alert severity="info">{t('OKX已返回成功响应，但账户余额数据为空。')}</Alert> : null}
        {positionError ? <Alert severity="error">{t('OKX持仓接口调用失败：')}{positionError}</Alert> : null}
        <TradingStatusStrip risk={risk} account={account} pendingCount={pendingCount} />
        <div className="mt-6 grid grid-cols-2 items-baseline gap-6 sm:grid-cols-4">
          <Stat label={t('账户模式')} value={formatStatus(accountMode)} hint={accountMessage} />
          <Stat label={t('账户总览')} value={balanceAvailable ? formatUSDT(account.equity) : t('不可用')} />
          <Stat label={t('可用余额')} value={balanceAvailable && availableBalance != null ? formatUSDT(availableBalance) : t('不可用')} />
          <Stat label={t('风控')} value={formatStatus(risk.passed)} hint={String(risk.rejectReason ?? formatStatus(risk.riskLevel) ?? t('无拦截'))} />
        </div>
        <div className="grid items-start gap-8 lg:grid-cols-[0.85fr_1.15fr]">
          <section>
            <h2 className="mb-2 text-[13px] font-semibold">{t('风控解释')}</h2>
            <Rule label={t('风控状态')} value={<StatusChip value={risk.passed as boolean | undefined} />} />
            <Rule label={t('风险等级')} value={formatStatus(risk.riskLevel)} />
            <Rule label={t('建议杠杆')} value={formatLeverage(risk.adjustedLeverage)} />
            <Rule label={t('最大单笔亏损')} value={formatUSDT(risk.maxLossAmount)} />
            <Rule label={t('拒绝原因')} value={String(risk.rejectReason ?? t('无拦截'))} />
          </section>
          <section>
            <h2 className="mb-2 text-[13px] font-semibold">{t('持仓快照')}</h2>
            {positionError ? <Alert severity="error">{t('持仓不可用，不代表账户空仓：')}{positionError}</Alert> : (
              <PositionSnapshotTable positions={positions} closingKey={closingKey} onClosePosition={setCloseDialogPosition} />
            )}
          </section>
        </div>
      </div>
      <ClosePositionDialog open={Boolean(closeDialogPosition)} position={closeDialogPosition} loading={Boolean(closingKey)} onCancel={() => setCloseDialogPosition(null)} onConfirm={() => void confirmClosePosition()} />
    </PageShell>
  );
}

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div>
      <div className="text-[12px] font-medium text-muted">{label}</div>
      <div className="mt-1 text-[22px] font-medium leading-7 tracking-[-0.03em] tabular-nums">{value}</div>
      {hint ? <div className="mt-1 text-[12px] text-faint">{hint}</div> : null}
    </div>
  );
}

function Rule({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4 border-t border-line py-2 text-[13px]">
      <span className="text-muted">{label}</span>
      <b className="text-right font-medium">{value}</b>
    </div>
  );
}
