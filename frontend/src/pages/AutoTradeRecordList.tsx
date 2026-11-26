import { PanelState } from '../components/PanelState';
import { PositionSnapshotTable } from '../components/PositionSnapshotTable';
import { accountPositionPnl, openPositions } from '../displayData';
import { ScrollableTable } from '../components/ScrollableTable';
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
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { panel } from '../components/surfaces';
import { PageHeader, PageShell } from '../components/PageShell';
import { StatusChip } from '../components/StatusChip';
import { formatTrend, formatAutoTradeReason, formatDecisionSource, formatEntryMode, formatLeverage, formatNumber, formatOpenDirection, formatPrice, formatScanAction, formatSignalType, formatStage, formatStatus, formatUSDT } from '../formatters';
import { getLanguage, t, useLanguage } from '../i18n';

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
  { value: 'WAITING_ENTRY', label: '等待入场条件' },
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
    if (silent) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    try {
      const [recordData, positionData] = await Promise.allSettled([
        quantApi.autoTradeRecords({
          ...filters,
          page: nextPage,
          size: pageSize,
        }),
        quantApi.positions(),
      ]);
      setPositions(positionData.status === 'fulfilled' && Array.isArray(positionData.value)
        ? positionData.value as Record<string, unknown>[] : null);
      if (recordData.status === 'rejected') throw recordData.reason;
      const result = recordData.value as Record<string, unknown>;
      setData({
        items: Array.isArray(result.items) ? (result.items as AutoTradeRecord[]) : [],
        total: Number(result.total ?? 0),
        page: Number(result.page ?? nextPage),
        size: Number(result.size ?? pageSize),
      });
      setPage(nextPage);
      setAppliedFilters(filters);
      setError('');
    } catch (loadError) {
      const message = loadError instanceof Error ? loadError.message : t("自动交易记录加载失败");
      setError(message);
      if (!silent) {
        notify(message, { type: 'error' });
      }
    } finally {
      inFlight.current = false;
      setLoading(false);
      setRefreshing(false);
    }
  }

  useEffect(() => {
    void load(0);
  }, []);

  const summary = useMemo(() => {
    const executed = data.items.filter((item) => item.status === 'EXECUTED' || item.status === 'ENTRY_SUBMITTED').length;
    const waiting = data.items.filter((item) => item.status === 'WAITING_ENTRY').length;
    return { executed, waiting };
  }, [data.items]);
  const pnl = accountPositionPnl(positions);

  const hasNext = (page + 1) * pageSize < data.total;

  return (
    <PageShell title={t("自动交易记录")}>
      <Stack spacing={2}>
        <PageHeader
          title={t("自动交易记录")}
          subtitle={t('共 {total} 条复盘记录，当前页等待入场 {waiting} 条、已提交 {submitted} 条，当前账户持仓浮盈亏 {pnl}', {
            total: data.total, waiting: summary.waiting, submitted: summary.executed,
            pnl: pnl == null ? t('不可用') : formatUSDT(pnl),
          })}
          filters={
            <>
              <FormControl size="small" sx={{ width: { xs: 1, sm: 172 } }}>
                <Select displayEmpty inputProps={{ 'aria-label': t("状态筛选") }} value={status} onChange={(event) => setStatus(event.target.value)}>
                  {statusOptions.map((option) => (
                    <MenuItem key={option.value || 'all'} value={option.value}>
                      {t(option.label)}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
              <TextField
                size="small"
                value={instId}
                onChange={(event) => setInstId(event.target.value)}
                placeholder={t("搜索合约")}
                inputProps={{ 'aria-label': t("搜索合约") }}
                InputProps={{
                  startAdornment: (
                    <InputAdornment position="start">
                      <SearchIcon fontSize="small" />
                    </InputAdornment>
                  ),
                }}
                sx={{ width: { xs: 1, sm: 220 } }}
              />
              <Button
                variant="outlined"
                size="small"
                startIcon={<SearchIcon />}
                onClick={() => void load(0, false, { status, instId: instId.trim() })}
                disabled={busy}
              >
                {t("查询")}
              </Button>
              <Button
                variant="outlined"
                size="small"
                startIcon={refreshing ? <CircularProgress size={14} /> : <RefreshIcon />}
                onClick={() => void load(page, true)}
                disabled={busy}
              >
                {t("刷新")}
              </Button>
            </>
          }
        />

        {error ? <Alert severity="error">{error}</Alert> : null}

        <Box sx={{ ...panel }}>
          {loading ? (
            <PanelState loading />
          ) : data.items.length === 0 ? (
            <PanelState>{t("暂无自动交易记录。")}</PanelState>
          ) : (
            <ScrollableTable label={t("自动交易记录")}>
              <TableHead>
                <TableRow>
                  <TableCell>{t("时间")}</TableCell>
                  <TableCell>{t("状态")}</TableCell>
                  <TableCell>{t("合约")}</TableCell>
                  <TableCell>{t("候选趋势")}</TableCell>
                  <TableCell>{t("订单方向")}</TableCell>
                  <TableCell align="right">{t("综合评分")}</TableCell>
                  <TableCell align="right">{t("最终排名分")}</TableCell>
                  <TableCell>{t("信号")}</TableCell>
                  <TableCell>{t("扫描动作")}</TableCell>
                  <TableCell>{t("AI 决策")}</TableCell>
                  <TableCell align="right">{t("保证金")}</TableCell>
                  <TableCell align="right">{t("杠杆")}</TableCell>
                  <TableCell align="right">{t("计划入场")}</TableCell>
                  <TableCell align="right">{t("候选毛盈亏比")}</TableCell>
                  <TableCell>{t("阶段")}</TableCell>
                  <TableCell>{t("原因")}</TableCell>
                  <TableCell>{t("OKX订单号")}</TableCell>
                  <TableCell>{t("AI 说明")}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {data.items.map((record) => (
                  <TableRow key={String(record.id)} hover>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatTime(record.createdAt)}</TableCell>
                    <TableCell>
                      <StatusChip value={String(record.status ?? '-')} />
                    </TableCell>
                    <TableCell sx={{ fontWeight: 900 }}>{String(record.instId ?? '-')}</TableCell>
                    <TableCell>{formatTrend(record.trendDirection)}</TableCell>
                    <TableCell>{record.status === 'SKIPPED' ? '—' : formatOpenDirection(record.action, record.posSide)}</TableCell>
                    <TableCell align="right">{formatNumber(record.candidateScore, 0)}</TableCell>
                    <TableCell align="right">{formatNumber(record.finalRankScore, 8)}</TableCell>
                    <TableCell>{formatSignalType(record.signalType)}</TableCell>
                    <TableCell>{formatScanAction(record.scanAction, record.reasonMessage ?? record.message)}</TableCell>
                    <TableCell sx={{ minWidth: 160 }}>
                      <Stack spacing={0.5}>
                        <Typography variant="caption" fontWeight={800}>
                          {formatDecisionSource(record.decisionSource)}
                        </Typography>
                        <Typography variant="caption" color="text.secondary">
                          {record.aiConfidence == null ? '-' : `${formatNumber(Number(record.aiConfidence) * 100, 0)}%`}
                          {' · '}{formatEntryMode(record.reasonCode === 'AI_WAIT_SKIPPED' ? 'NO_ENTRY' : record.aiEntryMode)}
                        </Typography>
                      </Stack>
                    </TableCell>
                    <TableCell align="right">{formatUSDT(record.marginAmount)}</TableCell>
                    <TableCell align="right">{formatLeverage(record.leverage)}</TableCell>
                    <TableCell align="right">{formatPrice(record.entryPrice ?? record.lastPrice)}</TableCell>
                    <TableCell align="right">{formatNumber(record.riskRewardRatio, 2)}</TableCell>
                    <TableCell>{formatStage(record.stage)}</TableCell>
                    <TableCell sx={{ minWidth: 180, maxWidth: 320, overflowWrap: 'anywhere' }}>
                      {Boolean(record.scanCoverage) && (
                        <Typography variant="caption" component="div" color="text.secondary">
                          {String(record.scanCoverage)}
                        </Typography>
                      )}
                      {formatAutoTradeReason(record.reasonMessage ?? record.message ?? record.reasonCode)}
                    </TableCell>
                    <TableCell sx={{ maxWidth: 150, overflowWrap: 'anywhere' }}>
                      {String(record.okxOrderId ?? '-')}
                    </TableCell>
                    <TableCell sx={{ minWidth: 260, maxWidth: 420, overflowWrap: 'anywhere' }}>
                      {formatAutoTradeReason(record.aiRejectionCode && record.aiRejectionCode !== 'NONE'
                        ? record.aiRejectionCode : record.selectionReason)}
                      {Boolean(record.aiRejectionCode && record.aiRejectionCode !== 'NONE' && record.selectionReason) && (
                        <Typography variant="caption" component="div" color="text.secondary">
                          {formatAutoTradeReason(record.selectionReason)}
                        </Typography>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </ScrollableTable>
          )}
          <Stack direction={{ xs: 'column', sm: 'row' }} gap={1} justifyContent="space-between" alignItems={{ xs: 'flex-start', sm: 'center' }} sx={{ p: 2, borderTop: 1, borderColor: 'divider' }}>
            <Typography variant="caption" color="text.secondary" fontWeight={700}>
              {t("第")} {page + 1} {t("页，每页")} {pageSize} {t("条，状态筛选：")}{formatStatus(appliedFilters.status || t("全部"))}
            </Typography>
            <Stack direction="row" spacing={1}>
              <Button size="small" variant="outlined" disabled={page <= 0 || busy} onClick={() => void load(page - 1, true)}>
                {t("上一页")}
              </Button>
              <Button size="small" variant="outlined" disabled={!hasNext || busy} onClick={() => void load(page + 1, true)}>
                {t("下一页")}
              </Button>
            </Stack>
          </Stack>
        </Box>
        <Box sx={{ ...panel, p: 2 }}>
          <Stack spacing={1.5}>
            <Typography component="h2" variant="h6">{t("当前账户持仓")}</Typography>
            {positions ? <PositionSnapshotTable positions={openPositions(positions)} /> : (
              loading ? <PanelState loading /> : <Alert severity="warning">{t("当前持仓数据不可用。")}</Alert>
            )}
          </Stack>
        </Box>
      </Stack>
    </PageShell>
  );
}

function formatTime(value: unknown) {
  const date = new Date(String(value ?? ''));
  if (Number.isNaN(date.getTime())) {
    return '-';
  }
  return date.toLocaleString(getLanguage());
}
