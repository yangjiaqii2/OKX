import { useEffect, useMemo, useRef, useState } from 'react';
import { useNotify } from 'react-admin';
import { Link } from 'react-router-dom';
import { quantApi } from '../api/quantApi';
import { PageHeader, PageShell } from '../components/PageShell';
import { PanelState } from '../components/PanelState';
import { ScrollableTable } from '../components/ScrollableTable';
import { StatusChip } from '../components/StatusChip';
import { Alert } from '../components/ui/alert';
import { Button } from '../components/ui/button';
import { Input, SelectInput } from '../components/ui/field';
import { TableBody, TableCell, TableHead, TableRow } from '../components/ui/table';
import { accountPositionPnl } from '../displayData';
import { formatAutoTradeReason, formatDecisionSource, formatEntryMode, formatLeverage, formatNumber, formatOpenDirection, formatPrice, formatScanAction, formatSignalType, formatStage, formatStatus, formatTrend, formatUSDT } from '../formatters';
import { getLanguage, t, useLanguage } from '../i18n';

type AutoTradeRecord = Record<string, unknown>;
type AutoTradePage = { items: AutoTradeRecord[]; total: number; page: number; size: number };
const pageSize = 50;
const statusOptions = [
  { value: '', label: '全部状态' },
  { value: 'EXECUTED', label: '已提交委托' },
  { value: 'ENTRY_SUBMITTED', label: '入场委托已提交' },
  { value: 'WAITING_ENTRY', label: '等待入场条件' },
  { value: 'SKIPPED', label: '已跳过' },
  { value: 'REJECTED', label: '已拒绝' },
  { value: 'FAILED', label: '失败' },
  { value: 'UNKNOWN_SUBMIT_STATUS', label: '提交未知' },
  { value: 'PROTECTION_FAILED', label: '保护单失败' },
  { value: 'CLOSE_SUBMITTED', label: '平仓已提交' },
  { value: 'CLOSED', label: '已关闭' },
];
const emptyPage: AutoTradePage = { items: [], total: 0, page: 0, size: pageSize };

export function AutoTradeRecordList() {
  useLanguage();
  const notify = useNotify();
  const [data, setData] = useState<AutoTradePage>(emptyPage);
  const [status, setStatus] = useState('');
  const [instId, setInstId] = useState('');
  const [appliedFilters, setAppliedFilters] = useState({ status: '', instId: '' });
  const [positions, setPositions] = useState<Record<string, unknown>[] | null>(null);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');
  const inFlight = useRef(false);
  const busy = loading || refreshing;

  async function load(nextPage = page, silent = false, filters = appliedFilters) {
    if (inFlight.current) return;
    inFlight.current = true;
    silent ? setRefreshing(true) : setLoading(true);
    try {
      const [recordData, positionData] = await Promise.allSettled([
        quantApi.autoTradeRecords({ ...filters, page: nextPage, size: pageSize }),
        quantApi.positions(),
      ]);
      setPositions(positionData.status === 'fulfilled' && Array.isArray(positionData.value) ? positionData.value as Record<string, unknown>[] : null);
      if (recordData.status === 'rejected') throw recordData.reason;
      const result = recordData.value as Record<string, unknown>;
      setData({
        items: Array.isArray(result.items) ? result.items as AutoTradeRecord[] : [],
        total: Number(result.total ?? 0),
        page: Number(result.page ?? nextPage),
        size: Number(result.size ?? pageSize),
      });
      setPage(nextPage);
      setAppliedFilters(filters);
      setError('');
    } catch (loadError) {
      const message = loadError instanceof Error ? loadError.message : t('自动交易记录加载失败');
      setError(message);
      if (!silent) notify(message, { type: 'error' });
    } finally {
      inFlight.current = false;
      setLoading(false);
      setRefreshing(false);
    }
  }

  useEffect(() => { void load(0); }, []);

  const summary = useMemo(() => ({
    executed: data.items.filter((item) => item.status === 'EXECUTED' || item.status === 'ENTRY_SUBMITTED').length,
    waiting: data.items.filter((item) => item.status === 'WAITING_ENTRY').length,
  }), [data.items]);
  const pnl = accountPositionPnl(positions);
  const hasNext = (page + 1) * pageSize < data.total;

  return (
    <PageShell title={t('自动交易记录')}>
      <PageHeader
        title={t('自动交易记录')}
        subtitle={t('共 {total} 条复盘记录，当前页等待入场 {waiting} 条、已提交 {submitted} 条，当前账户持仓浮盈亏 {pnl}', {
          total: data.total,
          waiting: summary.waiting,
          submitted: summary.executed,
          pnl: pnl == null ? t('不可用') : formatUSDT(pnl),
        })}
        onRefresh={() => void load(page, true)}
        refreshing={busy}
        filters={
          <form className="flex w-full flex-wrap items-center gap-2" onSubmit={(event) => {
            event.preventDefault();
            if (!busy) void load(0, false, { status, instId: instId.trim() });
          }}>
            <SelectInput className="w-full sm:w-44" aria-label={t('状态筛选')} value={status} onChange={(event) => setStatus(event.target.value)}>
              {statusOptions.map((option) => <option key={option.value || 'all'} value={option.value}>{t(option.label)}</option>)}
            </SelectInput>
            <Input className="h-8 w-full text-[13px] font-normal sm:w-56" value={instId} placeholder={t('搜索合约')} aria-label={t('搜索合约')} onChange={(event) => setInstId(event.target.value)} />
            <Button type="submit" variant="ghost" disabled={busy}>{t('查询')}</Button>
          </form>
        }
      />
      <div className="mt-4 space-y-3">
        {error ? <Alert severity="error">{error}</Alert> : null}
        {loading ? <PanelState loading /> : data.items.length === 0 ? <PanelState>{t('暂无自动交易记录。')}</PanelState> : (
          <ScrollableTable label={t('自动交易记录')}>
            <TableHead>
              <TableRow>
                {[t('时间'), t('Agent'), t('状态'), t('合约'), t('候选趋势'), t('订单方向'), t('综合评分'), t('最终排名分'), t('信号'), t('扫描动作'), t('AI 决策'), t('保证金'), t('杠杆'), t('计划入场'), t('候选毛盈亏比'), t('阶段'), t('原因'), t('OKX订单号'), t('AI 说明')].map((label) => <TableCell key={label}>{label}</TableCell>)}
              </TableRow>
            </TableHead>
            <TableBody>
              {data.items.map((record) => (
                <TableRow key={String(record.id)}>
                  <TableCell className="whitespace-nowrap">{formatTime(record.createdAt)}</TableCell>
                  <TableCell className="whitespace-nowrap font-medium text-ink">
                    {record.agentName ? String(record.agentName) : record.agentId ? `#${String(record.agentId)}` : '—'}
                  </TableCell>
                  <TableCell><StatusChip value={String(record.status ?? '-')} /></TableCell>
                  <TableCell className="font-semibold">{String(record.instId ?? '—')}</TableCell>
                  <TableCell>{formatTrend(record.trendDirection)}</TableCell>
                  <TableCell>{record.status === 'SKIPPED' ? '—' : formatOpenDirection(record.action, record.posSide)}</TableCell>
                  <TableCell align="right">{formatNumber(record.candidateScore, 0)}</TableCell>
                  <TableCell align="right">{formatNumber(record.finalRankScore, 2)}</TableCell>
                  <TableCell>{formatSignalType(record.signalType)}</TableCell>
                  <TableCell>{formatScanAction(record.scanAction, record.reasonMessage ?? record.message)}</TableCell>
                  <TableCell>
                    <div className="text-[12px] font-semibold">{formatDecisionSource(record.decisionSource)}</div>
                    <div className="text-[12px] text-muted">{record.aiConfidence == null ? '-' : `${formatNumber(Number(record.aiConfidence) * 100, 0)}%`} {formatEntryMode(record.reasonCode === 'AI_WAIT_SKIPPED' ? 'NO_ENTRY' : record.aiEntryMode)}</div>
                  </TableCell>
                  <TableCell align="right">{formatUSDT(record.marginAmount)}</TableCell>
                  <TableCell align="right">{formatLeverage(record.leverage)}</TableCell>
                  <TableCell align="right">{formatPrice(record.entryPrice ?? record.lastPrice)}</TableCell>
                  <TableCell align="right">{formatNumber(record.riskRewardRatio, 2)}</TableCell>
                  <TableCell>{formatStage(record.stage)}</TableCell>
                  <TableCell className="max-w-[320px] break-all">
                    {record.scanCoverage ? <div className="text-[12px] text-muted">{String(record.scanCoverage)}</div> : null}
                    {formatAutoTradeReason(record.reasonMessage ?? record.message ?? record.reasonCode)}
                  </TableCell>
                  <TableCell className="max-w-[150px] break-all">{String(record.okxOrderId ?? '-')}</TableCell>
                  <TableCell className="max-w-[420px] break-all">
                    {formatAutoTradeReason(record.aiRejectionCode && record.aiRejectionCode !== 'NONE' ? record.aiRejectionCode : record.selectionReason)}
                    {record.aiRejectionCode && record.aiRejectionCode !== 'NONE' && record.selectionReason ? <div className="text-[12px] text-muted">{formatAutoTradeReason(record.selectionReason)}</div> : null}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </ScrollableTable>
        )}
        <div className="flex flex-wrap items-center justify-between gap-2 text-[12px] text-muted">
          <span>{t('第')} {page + 1} {t('页，每页')} {pageSize} {t('条，状态筛选：')}{formatStatus(appliedFilters.status || t('全部'))}</span>
          <div className="flex gap-2">
            <Button variant="ghost" disabled={page <= 0 || busy} onClick={() => void load(page - 1, true)}>{t('上一页')}</Button>
            <Button variant="ghost" disabled={!hasNext || busy} onClick={() => void load(page + 1, true)}>{t('下一页')}</Button>
          </div>
        </div>
        <Link to="/positions" className="inline-block text-[13px] font-medium text-accent-ink hover:underline">{t('前往当前持仓')}</Link>
      </div>
    </PageShell>
  );
}

function formatTime(value: unknown) {
  const date = new Date(String(value ?? ''));
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString(getLanguage());
}
