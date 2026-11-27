import { useEffect, useState } from 'react';
import { quantApi } from '../api/quantApi';
import { PageHeader, PageShell } from '../components/PageShell';
import { PanelState } from '../components/PanelState';
import { ScrollableTable } from '../components/ScrollableTable';
import { StatusChip } from '../components/StatusChip';
import { Button } from '../components/ui/button';
import { Alert } from '../components/ui/alert';
import { TableBody, TableCell, TableHead, TableRow } from '../components/ui/table';
import { formatAutoTradeReason, formatPercent, formatPrice, formatSide, formatUSDT, pnlColor } from '../formatters';
import { t, useLanguage } from '../i18n';

type LifecycleRow = Record<string, unknown>;

export function AutoTradeLifecycleList() {
  useLanguage();
  const [rows, setRows] = useState<LifecycleRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');

  async function load(silent = false) {
    silent ? setRefreshing(true) : setLoading(true);
    try {
      const result = await quantApi.autoTradeLifecycle();
      if (!Array.isArray(result)) throw new Error(t('生命周期加载失败'));
      setRows(result as LifecycleRow[]);
      setError('');
    } catch (error) {
      setError(error instanceof Error ? error.message : t('生命周期加载失败'));
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }

  useEffect(() => { void load(); }, []);

  return (
    <PageShell title={t('自动交易生命周期')}>
      <PageHeader title={t('自动交易生命周期')} subtitle={`${t('自动交易仓位 ')}${rows.length}${t(' 条')}`} onRefresh={() => void load(true)} refreshing={loading || refreshing} />
      <div className="mt-4 space-y-3">
        <Alert severity="info">{t('预算占用来自入场委托或实际入场仓位；止损、TP1、TP2、TP3 是 reduceOnly 保护单，不计入新的开仓预算。')}</Alert>
        {error && (
          <Alert severity="error">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span>{t('生命周期加载失败')} · {error}</span>
              <Button disabled={refreshing} onClick={() => void load(true)}>{t('重试')}</Button>
            </div>
          </Alert>
        )}
        {loading ? <PanelState loading /> : rows.length === 0 ? (
          error ? null : <PanelState>{t('暂无记录。')}</PanelState>
        ) : (
          <ScrollableTable label={t('自动交易生命周期')}>
            <TableHead>
              <TableRow>
                <TableCell>{t('合约')}</TableCell>
                <TableCell>{t('方向')}</TableCell>
                <TableCell>{t('生命周期')}</TableCell>
                <TableCell>{t('入场状态')}</TableCell>
                <TableCell>{t('保护单状态')}</TableCell>
                <TableCell align="right">{t('成交数量')}</TableCell>
                <TableCell>{t('保护单')}</TableCell>
                <TableCell>{t('当前持仓')}</TableCell>
                <TableCell>{t('平仓')}</TableCell>
                <TableCell>{t('预算')}</TableCell>
                <TableCell>{t('最近事件')}</TableCell>
                <TableCell>{t('人工处理')}</TableCell>
                <TableCell align="right">{t('持仓时长')}</TableCell>
                <TableCell align="right">{t('入场价')}</TableCell>
                <TableCell align="right">{t('均价')}</TableCell>
                <TableCell align="right">{t('浮盈亏')}</TableCell>
                <TableCell align="right">{t('浮盈亏%')}</TableCell>
                <TableCell align="right">{t('预算占用')}</TableCell>
                <TableCell>{t('下一步')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((row, index) => {
                const position = row.positionLifecycle as { positionSyncAvailable?: boolean; open?: boolean } | undefined;
                const positionUnavailable = position?.positionSyncAvailable === false || row.lifecycleStatus === 'POSITION_SYNC_UNAVAILABLE';
                const showPosition = !positionUnavailable && position?.open !== false;
                const positionPlaceholder = positionUnavailable ? t('不可用') : '—';
                return (
                  <TableRow key={`${String(row.instId)}-${index}`}>
                    <TableCell className="font-semibold">{String(row.instId ?? '-')}</TableCell>
                    <TableCell>{formatSide(row.posSide)}</TableCell>
                    <TableCell><StatusChip value={String(row.lifecycleStatus ?? '-')} /></TableCell>
                    <TableCell>{String(row.entryOrderStatus ?? '-')}</TableCell>
                    <TableCell>{String(row.protectionOrderStatus ?? '-')}</TableCell>
                    <TableCell align="right">{formatPrice((row.entryOrder as Record<string, unknown> | undefined)?.filledSize)}</TableCell>
                    <TableCell>{formatProtectionSummary(row.protectionOrders)}</TableCell>
                    <TableCell>{formatPositionFact(row.positionLifecycle)}</TableCell>
                    <TableCell>{formatCloseFact(row.closePosition)}</TableCell>
                    <TableCell>{formatBudgetFact(row.budget)}</TableCell>
                    <TableCell className="max-w-[220px] break-all">{formatRecentEvent(row.recentEvents)}</TableCell>
                    <TableCell>{row.manualAttentionRequired ? <StatusChip value="MANUAL_ATTENTION_REQUIRED" /> : '-'}</TableCell>
                    <TableCell align="right">{formatDuration(row.holdDuration)}</TableCell>
                    <TableCell align="right">{formatPrice(row.entryPrice)}</TableCell>
                    <TableCell align="right">{showPosition ? formatPrice(row.avgPx) : positionPlaceholder}</TableCell>
                    <TableCell align="right" style={{ color: showPosition ? pnlColor(row.unrealizedPnl) : undefined }}>{showPosition ? formatUSDT(row.unrealizedPnl) : positionPlaceholder}</TableCell>
                    <TableCell align="right" style={{ color: showPosition ? pnlColor(row.unrealizedPnlPct) : undefined }}>{showPosition ? formatPercent(row.unrealizedPnlPct) : positionPlaceholder}</TableCell>
                    <TableCell align="right">{formatUSDT(row.budgetUsed)}</TableCell>
                    <TableCell className="max-w-[260px] break-all">{String(row.nextAction ?? '-')}</TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </ScrollableTable>
        )}
      </div>
    </PageShell>
  );
}

function formatDuration(value: unknown) {
  const raw = String(value ?? '');
  const match = raw.match(/PT(?:(\d+)H)?(?:(\d+)M)?/);
  if (!match) return '-';
  return `${Number(match[1] ?? 0)}h ${Number(match[2] ?? 0)}m`;
}

function formatProtectionSummary(value: unknown) {
  if (!Array.isArray(value) || value.length === 0) return '-';
  const statuses = value.map((item) => String((item as Record<string, unknown>).status ?? '-')).slice(0, 3).join('/');
  return `${value.length}${t('笔 ')}${statuses}`;
}

function formatPositionFact(value: unknown) {
  const fact = value as Record<string, unknown> | undefined;
  if (!fact) return '-';
  if (fact.positionSyncAvailable === false) return t('持仓同步不可用');
  if (!fact.open) return t('无持仓');
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
  return `${formatAutoTradeReason(event.eventType)} ${formatAutoTradeReason(event.reasonCode)}`.trim();
}
