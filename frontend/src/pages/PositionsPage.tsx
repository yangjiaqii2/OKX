import { useEffect, useState } from 'react';
import { useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { ClosePositionDialog } from '../components/ClosePositionDialog';
import { PageHeader, PageShell } from '../components/PageShell';
import { PanelState } from '../components/PanelState';
import { PositionSnapshotTable, positionKey } from '../components/PositionSnapshotTable';
import { Alert } from '../components/ui/alert';
import { t, useLanguage } from '../i18n';

export function PositionsPage() {
  useLanguage();
  const notify = useNotify();
  const [positions, setPositions] = useState<Record<string, unknown>[]>([]);
  const [selected, setSelected] = useState<Record<string, unknown> | null>(null);
  const [loading, setLoading] = useState(true);
  const [closing, setClosing] = useState('');
  const [error, setError] = useState('');

  async function load() {
    setLoading(true);
    try {
      const data = await quantApi.positions();
      setPositions(Array.isArray(data) ? data as Record<string, unknown>[] : []);
      setError('');
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : t('持仓加载失败'));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  async function close() {
    if (!selected) return;
    setClosing(positionKey(selected));
    try {
      await quantApi.closePosition({
        instId: String(selected.instId ?? ''),
        posSide: String(selected.posSide ?? ''),
        marginMode: String(selected.marginMode ?? ''),
      });
      notify(t('平仓请求已提交，请等待 OKX 最终状态'), { type: 'success' });
      setSelected(null);
      await load();
    } catch (loadError) {
      notify(loadError instanceof Error ? loadError.message : t('平仓失败'), { type: 'error' });
    } finally {
      setClosing('');
    }
  }

  return (
    <PageShell title={t('当前持仓')}>
      <PageHeader title={t('当前持仓')} subtitle={t('OKX 是真实资金仓位权威；UNMANAGED 不会自动归属策略。')} onRefresh={() => void load()} refreshing={loading} />
      <div className="mt-4 space-y-3">
        {error ? <Alert severity="error">{error}</Alert> : null}
        {positions.some((position) => position.ownership === 'UNMANAGED') ? <Alert severity="error">{t('检测到外部/未管理仓位，冲突自动交易将被阻断。')}</Alert> : null}
        {loading ? <PanelState loading /> : <PositionSnapshotTable positions={positions} closingKey={closing} onClosePosition={setSelected} />}
      </div>
      <ClosePositionDialog open={Boolean(selected)} position={selected} loading={Boolean(closing)} onCancel={() => setSelected(null)} onConfirm={() => void close()} />
    </PageShell>
  );
}
