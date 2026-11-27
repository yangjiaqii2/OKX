import { useEffect, useState } from 'react';
import { useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { PageHeader, PageShell } from '../components/PageShell';
import { PanelState } from '../components/PanelState';
import { ScrollableTable } from '../components/ScrollableTable';
import { StatusChip } from '../components/StatusChip';
import { Alert } from '../components/ui/alert';
import { Button } from '../components/ui/button';
import { TableBody, TableCell, TableHead, TableRow } from '../components/ui/table';
import { formatPrice, formatSide, formatUSDT, pnlColor } from '../formatters';
import { getLanguage, t, useLanguage } from '../i18n';

type CloseRecord = Record<string, unknown>;
type CloseRecordPage = { items: CloseRecord[]; total: number; page: number; size: number };
const pageSize = 100;

export function ClosePositionRecordList() {
  useLanguage();
  const notify = useNotify();
  const [page, setPage] = useState<CloseRecordPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  async function load(nextPage = page?.page ?? 0) {
    setLoading(true);
    setError('');
    try {
      const result = await quantApi.closePositionRecords({ page: nextPage, size: pageSize }) as CloseRecordPage;
      if (!Array.isArray(result.items) || !Number.isSafeInteger(result.total) || result.total < 0 || result.page !== nextPage || !Number.isSafeInteger(result.size) || result.size < 1) {
        throw new Error(t('平仓记录响应格式异常'));
      }
      setPage(result);
    } catch (loadError) {
      const message = loadError instanceof Error ? loadError.message : t('平仓记录加载失败');
      setError(message);
      notify(message, { type: 'error' });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  return (
    <PageShell title={t('平仓记录')}>
      <PageHeader
        title={t('平仓记录')}
        subtitle={page ? `${t('共 ')}${page.total}${t(' 条平仓请求')}` : loading ? t('正在加载平仓记录') : t('平仓记录不可用')}
        onRefresh={() => void load()}
        refreshing={loading}
      />
      <div className="mt-4 space-y-3">
        {error ? <Alert severity="error">{error}</Alert> : null}
        {loading && !page ? <PanelState loading /> : page == null ? null : page.items.length === 0 ? <PanelState>{t('暂无记录。')}</PanelState> : (
          <ScrollableTable label={t('平仓记录')}>
            <TableHead>
              <TableRow>
                <TableCell>{t('时间')}</TableCell>
                <TableCell>{t('状态')}</TableCell>
                <TableCell>{t('合约')}</TableCell>
                <TableCell>{t('方向')}</TableCell>
                <TableCell align="right">{t('数量')}</TableCell>
                <TableCell align="right">{t('均价')}</TableCell>
                <TableCell align="right">{t('已实现盈亏')}</TableCell>
                <TableCell>{t('来源')}</TableCell>
                <TableCell>{t('OKX订单号')}</TableCell>
                <TableCell>{t('错误')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {page.items.map((record) => (
                <TableRow key={String(record.id)}>
                  <TableCell className="whitespace-nowrap">{formatTime(record.createdAt)}</TableCell>
                  <TableCell><StatusChip value={String(record.status ?? '-')} /></TableCell>
                  <TableCell className="font-semibold">{String(record.instId ?? '-')}</TableCell>
                  <TableCell>{formatSide(record.posSide)} / {formatSide(record.marginMode)}</TableCell>
                  <TableCell align="right">{formatPrice(record.size)}</TableCell>
                  <TableCell align="right">{formatPrice(record.avgPx)}</TableCell>
                  <TableCell align="right" style={{ color: pnlColor(record.realizedPnl) }}>{formatUSDT(record.realizedPnl)}</TableCell>
                  <TableCell>{String(record.source ?? '-')}</TableCell>
                  <TableCell className="max-w-[150px] break-all">{String(record.closeOrderId ?? '-')}</TableCell>
                  <TableCell className="max-w-[260px] break-all">{String(record.errorMessage ?? '-')}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </ScrollableTable>
        )}
        {page && page.total > 0 ? (
          <div className="flex flex-wrap items-center justify-between gap-2 text-[13px] text-muted">
            <span>{t('第')} {page.page + 1} / {Math.ceil(page.total / page.size)} {t('页')}</span>
            <div className="flex gap-2">
              <Button variant="ghost" disabled={loading || page.page === 0} onClick={() => void load(page.page - 1)}>{t('上一页')}</Button>
              <Button variant="ghost" disabled={loading || (page.page + 1) * page.size >= page.total} onClick={() => void load(page.page + 1)}>{t('下一页')}</Button>
            </div>
          </div>
        ) : null}
      </div>
    </PageShell>
  );
}

function formatTime(value: unknown) {
  const date = new Date(String(value ?? ''));
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString(getLanguage());
}
