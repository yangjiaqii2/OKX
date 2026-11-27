import { useEffect, useState } from 'react';
import { quantApi } from '../api/quantApi';
import { ClosePositionDialog, useClosePosition } from '../components/ClosePositionDialog';
import { PageHeader, PageShell } from '../components/PageShell';
import { PanelState } from '../components/PanelState';
import { PositionSnapshotTable } from '../components/PositionSnapshotTable';
import { Alert } from '../components/ui/alert';
import { openPositions } from '../displayData';
import { t, useLanguage } from '../i18n';

export function PositionsPage() {
  useLanguage();
  const [positions, setPositions] = useState<Record<string, unknown>[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const closePosition = useClosePosition(load);

  async function load() {
    setLoading(true);
    try {
      const data = await quantApi.positions();
      setPositions(openPositions(Array.isArray(data) ? data as Record<string, unknown>[] : []));
      setError('');
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : t('持仓加载失败'));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  return (
    <PageShell title={t('当前持仓')}>
      <PageHeader title={t('当前持仓')} subtitle={t('OKX 是真实资金仓位权威；UNMANAGED 不会自动归属策略。')} onRefresh={() => { void load(); void closePosition.refreshMode(); }} refreshing={loading} />
      <div className="mt-4 space-y-3">
        {error ? <Alert severity="error">{error}</Alert> : null}
        {positions.some((position) => position.ownership === 'UNMANAGED') ? <Alert severity="error">{t('检测到外部/未管理仓位，冲突自动交易将被阻断。')}</Alert> : null}
        {loading ? <PanelState loading /> : <PositionSnapshotTable positions={positions} closing={closePosition.loading} closeDisabledReason={closePosition.disabledReason} onClosePosition={closePosition.select} />}
      </div>
      <ClosePositionDialog {...closePosition.dialog} />
    </PageShell>
  );
}
