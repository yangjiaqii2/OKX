import RefreshIcon from '@mui/icons-material/Refresh';
import { Box, Button, CircularProgress, Stack, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { glassCard } from '../components/glass';
import { PageHeader, PageShell } from '../components/PageShell';
import { StatusChip } from '../components/StatusChip';
import { formatPrice, formatSide } from '../formatters';

type OrderRow = Record<string, unknown>;

export function CurrentOkxOrderList() {
  const notify = useNotify();
  const [tab, setTab] = useState(0);
  const [normalOrders, setNormalOrders] = useState<OrderRow[]>([]);
  const [algoOrders, setAlgoOrders] = useState<OrderRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  async function load(silent = false) {
    silent ? setRefreshing(true) : setLoading(true);
    try {
      const [normal, algo] = await Promise.all([
        quantApi.currentOkxOrders(),
        quantApi.currentOkxAlgoOrders(),
      ]);
      setNormalOrders(Array.isArray(normal) ? normal as OrderRow[] : []);
      setAlgoOrders(Array.isArray(algo) ? algo as OrderRow[] : []);
    } catch (error) {
      notify(error instanceof Error ? error.message : 'OKX委托加载失败', { type: 'error' });
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  const rows = tab === 0 ? normalOrders : algoOrders;

  return (
    <PageShell title="当前委托/保护单">
      <Stack spacing={2}>
        <PageHeader
          title="当前委托/保护单"
          subtitle={`普通委托 ${normalOrders.length} 条，保护单 ${algoOrders.length} 条`}
          eyebrow="OKX Live Orders"
          actions={
            <Button
              variant="outlined"
              size="small"
              startIcon={refreshing ? <CircularProgress size={14} /> : <RefreshIcon />}
              onClick={() => void load(true)}
              disabled={refreshing}
            >
              刷新
            </Button>
          }
        />

        <Box sx={{ ...glassCard }}>
          <Tabs value={tab} onChange={(_, value) => setTab(value)} sx={{ px: 1 }}>
            <Tab label="普通委托" />
            <Tab label="保护单" />
          </Tabs>
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
                    <TableCell>ID</TableCell>
                    <TableCell>角色</TableCell>
                    <TableCell>方向</TableCell>
                    <TableCell>类型</TableCell>
                    <TableCell align="right">数量</TableCell>
                    <TableCell align="right">价格</TableCell>
                    <TableCell align="right">触发价</TableCell>
                    <TableCell>状态</TableCell>
                    <TableCell>创建时间</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {rows.map((row, index) => (
                    <TableRow key={String(row.ordId ?? row.algoId ?? index)} hover>
                      <TableCell sx={{ fontWeight: 900 }}>{String(row.instId ?? '-')}</TableCell>
                      <TableCell sx={{ fontFamily: '"JetBrains Mono", monospace', maxWidth: 160, overflowWrap: 'anywhere' }}>
                        {String(row.ordId ?? row.algoId ?? row.clOrdId ?? row.algoClOrdId ?? '-')}
                      </TableCell>
                      <TableCell>{String(row.role ?? '-')}</TableCell>
                      <TableCell>{formatSide(row.side)} / {formatSide(row.posSide)}</TableCell>
                      <TableCell>{formatSide(row.ordType)}</TableCell>
                      <TableCell align="right">{formatPrice(row.size)}</TableCell>
                      <TableCell align="right">{formatPrice(row.price)}</TableCell>
                      <TableCell align="right">{formatPrice(row.triggerPrice)}</TableCell>
                      <TableCell><StatusChip value={String(row.status ?? '-')} /></TableCell>
                      <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatTime(row.createdAt)}</TableCell>
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
