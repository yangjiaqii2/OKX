import { useEffect, useState } from 'react';
import { useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { PageHeader, PageShell } from '../components/PageShell';
import { PanelState } from '../components/PanelState';
import { ScrollableTable } from '../components/ScrollableTable';
import { StatusChip } from '../components/StatusChip';
import { Alert } from '../components/ui/alert';
import { TableBody, TableCell, TableHead, TableRow } from '../components/ui/table';
import { directionTone, formatPrice, formatSide } from '../formatters';
import { cn } from '../lib/cn';
import { getLanguage, t, useLanguage } from '../i18n';

type OrderRow = Record<string, unknown>;

export function CurrentOkxOrderList() {
  useLanguage();
  const notify = useNotify();
  const [tab, setTab] = useState(0);
  const [normalOrders, setNormalOrders] = useState<OrderRow[]>([]);
  const [algoOrders, setAlgoOrders] = useState<OrderRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [availabilityMessage, setAvailabilityMessage] = useState('');

  async function load(silent = false) {
    silent ? setRefreshing(true) : setLoading(true);
    try {
      const binding = await quantApi.accountBindingStatus() as { bound?: boolean };
      if (!binding.bound) {
        setNormalOrders([]);
        setAlgoOrders([]);
        setAvailabilityMessage(t('当前账号未绑定 OKX API，无法查询交易所活动委托。'));
        return;
      }
      const [normal, algo] = await Promise.all([quantApi.currentOkxOrders(), quantApi.currentOkxAlgoOrders()]);
      setNormalOrders(Array.isArray(normal) ? normal as OrderRow[] : []);
      setAlgoOrders(Array.isArray(algo) ? algo as OrderRow[] : []);
      setAvailabilityMessage('');
    } catch (error) {
      setAvailabilityMessage(t('OKX 活动委托查询失败；已显示的数据可能过期，请检查接口状态后重试。'));
      notify(error instanceof Error ? error.message : t('OKX委托加载失败'), { type: 'error' });
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }

  useEffect(() => { void load(); }, []);
  const rows = tab === 0 ? normalOrders : algoOrders;

  return (
    <PageShell title={t('当前委托/保护单')}>
      <PageHeader
        title={t('当前委托/保护单')}
        subtitle={t('普通委托 {standard} 条，保护单 {protective} 条', { standard: normalOrders.length, protective: algoOrders.length })}
        onRefresh={() => void load(true)}
        refreshing={refreshing}
      />
      <div className="mt-4 space-y-3">
        {availabilityMessage ? <Alert severity="warning">{availabilityMessage}</Alert> : null}
        <div className="flex gap-4 text-[13px] font-medium">
          {[t('普通委托'), t('保护单')].map((label, index) => (
            <button key={label} type="button" className={cn('border-b-2 pb-1', tab === index ? 'border-accent text-accent-ink' : 'border-transparent text-muted')} onClick={() => setTab(index)}>{label}</button>
          ))}
        </div>
        {loading ? <PanelState loading /> : rows.length === 0 ? <PanelState>{t('暂无记录。')}</PanelState> : (
          <ScrollableTable label={t('当前委托/保护单')}>
            <TableHead>
              <TableRow>
                <TableCell>{t('合约')}</TableCell>
                <TableCell>ID</TableCell>
                <TableCell>{t('角色')}</TableCell>
                <TableCell>{t('方向')}</TableCell>
                <TableCell>{t('类型')}</TableCell>
                <TableCell align="right">{t('数量')}</TableCell>
                <TableCell align="right">{t('价格')}</TableCell>
                <TableCell align="right">{t('触发价')}</TableCell>
                <TableCell>{t('状态')}</TableCell>
                <TableCell>{t('创建时间')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((row, index) => {
                const orderId = [row.ordId, row.algoId, row.clOrdId, row.algoClOrdId].find((value) => value != null && String(value).trim() !== '');
                const tone = directionTone(row.side);
                return (
                  <TableRow key={String(orderId ?? index)}>
                    <TableCell className="font-semibold">{String(row.instId ?? '-')}</TableCell>
                    <TableCell className="max-w-40 break-all">{String(orderId ?? '—')}</TableCell>
                    <TableCell>{String(row.role ?? '-')}</TableCell>
                    <TableCell className={tone === 'success' ? 'text-up' : tone === 'error' ? 'text-down' : undefined}>{formatSide(row.side)} / {formatSide(row.posSide)}</TableCell>
                    <TableCell>{formatSide(row.ordType)}</TableCell>
                    <TableCell align="right">{formatPrice(row.size)}</TableCell>
                    <TableCell align="right">{Number(row.price) > 0 && String(row.ordType).toLowerCase() !== 'market' ? formatPrice(row.price) : '—'}</TableCell>
                    <TableCell align="right">{Number(row.triggerPrice) > 0 ? formatPrice(row.triggerPrice) : '—'}</TableCell>
                    <TableCell><StatusChip value={String(row.status ?? '-')} /></TableCell>
                    <TableCell className="whitespace-nowrap">{formatTime(row.createdAt)}</TableCell>
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

function formatTime(value: unknown) {
  const date = new Date(String(value ?? ''));
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString(getLanguage());
}
