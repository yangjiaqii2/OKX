import { PanelState } from '../components/PanelState';
import { ScrollableTable } from '../components/ScrollableTable';
import RefreshIcon from '@mui/icons-material/Refresh';
import { Alert, Box, Button, CircularProgress, Stack, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { panel } from '../components/surfaces';
import { PageHeader, PageShell } from '../components/PageShell';
import { StatusChip } from '../components/StatusChip';
import { formatPrice, formatSide, formatUSDT, pnlColor } from '../formatters';

type CloseRecord = Record<string, unknown>;
type CloseRecordPage = { items: CloseRecord[]; total: number; page: number; size: number };
const pageSize = 100;

export function ClosePositionRecordList() {
  const notify = useNotify();
  const [page, setPage] = useState<CloseRecordPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  async function load(nextPage = page?.page ?? 0) {
    setLoading(true);
    setError('');
    try {
      const result = await quantApi.closePositionRecords({ page: nextPage, size: pageSize }) as CloseRecordPage;
      if (!Array.isArray(result.items) || !Number.isSafeInteger(result.total) || result.total < 0
        || result.page !== nextPage || !Number.isSafeInteger(result.size) || result.size < 1) {
        throw new Error('平仓记录响应格式异常');
      }
      setPage(result);
    } catch (loadError) {
      const message = loadError instanceof Error ? loadError.message : '平仓记录加载失败';
      setError(message);
      notify(message, { type: 'error' });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  return (
    <PageShell title="平仓记录">
      <Stack spacing={2}>
        <PageHeader
          title="平仓记录"
          subtitle={page ? `共 ${page.total} 条平仓请求` : loading ? '正在加载平仓记录' : '平仓记录不可用'}
          actions={
            <Button variant="outlined" size="small" startIcon={loading ? <CircularProgress size={14} /> : <RefreshIcon />} onClick={() => void load()} disabled={loading}>
              刷新
            </Button>
          }
        />
        {error ? <Alert severity="error">{error}</Alert> : null}
        <Box sx={{ ...panel }}>
          {loading && !page ? (
            <PanelState loading />
          ) : page == null ? null : page.items.length === 0 ? (
            <PanelState>暂无记录。</PanelState>
          ) : (
            <ScrollableTable label="平仓记录">
              <TableHead>
                <TableRow>
                  <TableCell>时间</TableCell>
                  <TableCell>状态</TableCell>
                  <TableCell>合约</TableCell>
                  <TableCell>方向</TableCell>
                  <TableCell align="right">数量</TableCell>
                  <TableCell align="right">均价</TableCell>
                  <TableCell align="right">已实现盈亏</TableCell>
                  <TableCell>来源</TableCell>
                  <TableCell>OKX订单号</TableCell>
                  <TableCell>错误</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {page.items.map((record) => (
                  <TableRow key={String(record.id)} hover>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatTime(record.createdAt)}</TableCell>
                    <TableCell><StatusChip value={String(record.status ?? '-')} /></TableCell>
                    <TableCell sx={{ fontWeight: 900 }}>{String(record.instId ?? '-')}</TableCell>
                    <TableCell>{formatSide(record.posSide)} / {formatSide(record.marginMode)}</TableCell>
                    <TableCell align="right">{formatPrice(record.size)}</TableCell>
                    <TableCell align="right">{formatPrice(record.avgPx)}</TableCell>
                    <TableCell align="right" sx={{ color: pnlColor(record.realizedPnl) }}>{formatUSDT(record.realizedPnl)}</TableCell>
                    <TableCell>{String(record.source ?? '-')}</TableCell>
                    <TableCell sx={{ maxWidth: 150, overflowWrap: 'anywhere' }}>
                      {String(record.closeOrderId ?? '-')}
                    </TableCell>
                    <TableCell sx={{ maxWidth: 260, overflowWrap: 'anywhere' }}>{String(record.errorMessage ?? '-')}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </ScrollableTable>
          )}
          {page && page.total > 0 ? (
            <Stack direction="row" flexWrap="wrap" justifyContent="space-between" alignItems="center" gap={1}
              sx={{ p: 2, borderTop: 1, borderColor: 'divider' }}>
              <Typography variant="body2" color="text.secondary">
                第 {page.page + 1} / {Math.ceil(page.total / page.size)} 页
              </Typography>
              <Stack direction="row" gap={1}>
                <Button variant="outlined" size="small" disabled={loading || page.page === 0}
                  onClick={() => void load(page.page - 1)}>上一页</Button>
                <Button variant="outlined" size="small" disabled={loading || (page.page + 1) * page.size >= page.total}
                  onClick={() => void load(page.page + 1)}>下一页</Button>
              </Stack>
            </Stack>
          ) : null}
        </Box>
      </Stack>
    </PageShell>
  );
}

function formatTime(value: unknown) {
  const date = new Date(String(value ?? ''));
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString();
}
