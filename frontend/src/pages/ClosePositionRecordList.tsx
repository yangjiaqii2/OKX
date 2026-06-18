import RefreshIcon from '@mui/icons-material/Refresh';
import { Box, Button, CircularProgress, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { glassCard } from '../components/glass';
import { PageHeader, PageShell } from '../components/PageShell';
import { StatusChip } from '../components/StatusChip';
import { formatPrice, formatSide, formatUSDT } from '../formatters';

type CloseRecord = Record<string, unknown>;

export function ClosePositionRecordList() {
  const notify = useNotify();
  const [records, setRecords] = useState<CloseRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  async function load(silent = false) {
    silent ? setRefreshing(true) : setLoading(true);
    try {
      const result = await quantApi.closePositionRecords({ size: 100 });
      const page = result as Record<string, unknown>;
      setRecords(Array.isArray(page.items) ? page.items as CloseRecord[] : []);
    } catch (error) {
      notify(error instanceof Error ? error.message : '平仓记录加载失败', { type: 'error' });
    } finally {
      setLoading(false);
      setRefreshing(false);
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
          subtitle={`共 ${records.length} 条平仓请求`}
          eyebrow="Close Audit"
          actions={
            <Button variant="outlined" size="small" startIcon={refreshing ? <CircularProgress size={14} /> : <RefreshIcon />} onClick={() => void load(true)} disabled={refreshing}>
              刷新
            </Button>
          }
        />
        <Box sx={{ ...glassCard }}>
          {loading ? (
            <Box sx={{ minHeight: 220, display: 'grid', placeItems: 'center' }}>
              <CircularProgress size={28} />
            </Box>
          ) : records.length === 0 ? (
            <Box sx={{ minHeight: 180, display: 'grid', placeItems: 'center', p: 3 }}>
              <Typography color="text.secondary">暂无记录。</Typography>
            </Box>
          ) : (
            <TableContainer>
              <Table size="small">
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
                  {records.map((record) => (
                    <TableRow key={String(record.id)} hover>
                      <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatTime(record.createdAt)}</TableCell>
                      <TableCell><StatusChip value={String(record.status ?? '-')} /></TableCell>
                      <TableCell sx={{ fontWeight: 900 }}>{String(record.instId ?? '-')}</TableCell>
                      <TableCell>{formatSide(record.posSide)} / {formatSide(record.marginMode)}</TableCell>
                      <TableCell align="right">{formatPrice(record.size)}</TableCell>
                      <TableCell align="right">{formatPrice(record.avgPx)}</TableCell>
                      <TableCell align="right">{formatUSDT(record.realizedPnl)}</TableCell>
                      <TableCell>{String(record.source ?? '-')}</TableCell>
                      <TableCell sx={{ fontFamily: '"JetBrains Mono", monospace', maxWidth: 150, overflowWrap: 'anywhere' }}>
                        {String(record.closeOrderId ?? '-')}
                      </TableCell>
                      <TableCell sx={{ maxWidth: 260, overflowWrap: 'anywhere' }}>{String(record.errorMessage ?? '-')}</TableCell>
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

function formatTime(value: unknown) {
  const date = new Date(String(value ?? ''));
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString();
}
