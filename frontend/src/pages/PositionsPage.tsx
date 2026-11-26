import RefreshIcon from '@mui/icons-material/Refresh';
import { Alert, Button, Stack } from '@mui/material';
import { useEffect, useState } from 'react';
import { useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { ClosePositionDialog } from '../components/ClosePositionDialog';
import { PanelState } from '../components/PanelState';
import { panel } from '../components/surfaces';
import { PageHeader, PageShell } from '../components/PageShell';
import { PositionSnapshotTable, positionKey } from '../components/PositionSnapshotTable';
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
    try { const data = await quantApi.positions(); setPositions(Array.isArray(data) ? data as Record<string, unknown>[] : []); setError(''); }
    catch (e) { setError(e instanceof Error ? e.message : t("持仓加载失败")); }
    finally { setLoading(false); }
  }
  useEffect(() => { void load(); }, []);
  async function close() {
    if (!selected) return;
    setClosing(positionKey(selected));
    try {
      await quantApi.closePosition({ instId: String(selected.instId ?? ''), posSide: String(selected.posSide ?? ''), marginMode: String(selected.marginMode ?? '') });
      notify(t("平仓请求已提交，请等待 OKX 最终状态"), { type: 'success' }); setSelected(null); await load();
    } catch (e) { notify(e instanceof Error ? e.message : t("平仓失败"), { type: 'error' }); }
    finally { setClosing(''); }
  }
  return <PageShell title={t("当前持仓")}><Stack spacing={2}>
    <PageHeader title={t("当前持仓")} subtitle={t("OKX 是真实资金仓位权威；UNMANAGED 不会自动归属策略。")}
      actions={<Button startIcon={<RefreshIcon />} variant="outlined" onClick={() => void load()}>{t("刷新")}</Button>} />
    {error && <Alert severity="error">{error}</Alert>}
    {positions.some((p) => p.ownership === 'UNMANAGED') && <Alert severity="error">{t("检测到外部/未管理仓位，冲突自动交易将被阻断。")}</Alert>}
    {loading ? <PanelState loading sx={panel} />
      : <PositionSnapshotTable positions={positions} closingKey={closing} onClosePosition={setSelected} />}
  </Stack><ClosePositionDialog open={Boolean(selected)} position={selected} loading={Boolean(closing)}
    onCancel={() => setSelected(null)} onConfirm={() => void close()} /></PageShell>;
}
