import { useEffect, useState } from 'react';
import { useNotify } from 'react-admin';
import { getAuthRole } from '../api/auth';
import { quantApi } from '../api/quantApi';
import { PageHeader, PageShell } from '../components/PageShell';
import { PanelState } from '../components/PanelState';
import { Alert } from '../components/ui/alert';
import { Button } from '../components/ui/button';
import { ConfirmDialog } from '../components/ui/confirm-dialog';
import { getLanguage, t, useLanguage } from '../i18n';

type SystemStatus = {
  emergencyStop?: boolean;
  autoTradeEnabled?: boolean;
  updatedAt?: string;
};

export function SystemControlPage() {
  useLanguage();
  const notify = useNotify();
  const [emergencyOpen, setEmergencyOpen] = useState(false);
  const [resumeOpen, setResumeOpen] = useState(false);
  const [autoDisableOpen, setAutoDisableOpen] = useState(false);
  const [status, setStatus] = useState<SystemStatus>();
  const [statusLoading, setStatusLoading] = useState(true);
  const [statusError, setStatusError] = useState('');
  const [forbidden, setForbidden] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const isAdmin = getAuthRole() === 'ADMIN' && !forbidden;
  const disabled = !status || statusLoading || submitting;

  function reportError(error: unknown, fallback: string) {
    const denied = (error as { status?: number } | null)?.status === 403;
    if (denied) setForbidden(true);
    const message = denied ? t('无权限，仅管理员可以操作系统控制。') : error instanceof Error ? error.message : fallback;
    notify(message, { type: 'error' });
    return message;
  }

  async function loadStatus() {
    setStatusLoading(true);
    setStatusError('');
    try {
      const nextStatus = (await quantApi.systemStatus()) as SystemStatus;
      if (typeof nextStatus?.emergencyStop !== 'boolean' || typeof nextStatus.autoTradeEnabled !== 'boolean') {
        throw new Error(t('系统状态加载失败'));
      }
      setStatus(nextStatus);
    } catch (error) {
      setStatus(undefined);
      setStatusError(reportError(error, t('系统状态加载失败')));
    } finally {
      setStatusLoading(false);
    }
  }

  async function emergencyStop() {
    if (!isAdmin || disabled || status?.emergencyStop !== false) return;
    setSubmitting(true);
    try {
      setStatus((await quantApi.emergencyStop()) as SystemStatus);
      notify(t('已触发紧急停止'), { type: 'warning' });
    } catch (error) {
      reportError(error, t('紧急停止失败'));
    } finally {
      setSubmitting(false);
      setEmergencyOpen(false);
    }
  }

  async function resume() {
    if (!isAdmin || disabled || status?.emergencyStop !== true) return;
    setSubmitting(true);
    try {
      setStatus((await quantApi.resume()) as SystemStatus);
      notify(t('系统已恢复运行'), { type: 'success' });
    } catch (error) {
      reportError(error, t('恢复失败'));
    } finally {
      setSubmitting(false);
      setResumeOpen(false);
    }
  }

  async function disableAutoTrade() {
    if (!isAdmin || disabled || status?.autoTradeEnabled !== true) return;
    setSubmitting(true);
    try {
      setStatus((await quantApi.disableAutoTrade()) as SystemStatus);
      notify(t('旧版全局自动交易已关闭'), { type: 'success' });
    } catch (error) {
      reportError(error, t('自动交易关闭失败'));
    } finally {
      setSubmitting(false);
      setAutoDisableOpen(false);
    }
  }

  useEffect(() => { if (isAdmin) void loadStatus(); }, [isAdmin]);

  if (!isAdmin) {
    return <PageShell title={t('系统控制')} maxWidth={760}>
      <PageHeader title={t('系统控制')} />
      <Alert className="mt-5" severity="warning">{t('无权限，仅管理员可以操作系统控制。')}</Alert>
    </PageShell>;
  }

  const updatedAt = status?.updatedAt ? new Date(status.updatedAt) : null;

  return (
    <PageShell title={t('系统控制')} maxWidth={760}>
      <PageHeader title={t('系统控制')} subtitle={t('集中管理平台紧急停止总闸与全平台交易 Agent 运行状态。所有自动交易均由「我的 Agent」独立驱动。')} />
      <Alert className="mt-5" severity="warning">{t('紧急停止不会删除数据，但会立即阻断所有交易 Agent 与订单执行。实盘环境下请先确认当前 OKX 未留有未处理订单。')}</Alert>
      {statusLoading && <PanelState loading />}
      {statusError && (
        <Alert className="mt-4" severity="error">
          {statusError} <Button onClick={() => void loadStatus()}>{t('重试')}</Button>
        </Alert>
      )}
      {status?.autoTradeEnabled ? (
        <Alert className="mt-4" severity="warning">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <span>{t('检测到旧版单账户全局自动交易仍处于开启状态，建议立即关闭以避免与个人交易 Agent 重复下单。')}</span>
            <Button variant="danger" size="sm" disabled={disabled} onClick={() => setAutoDisableOpen(true)}>
              {t('立即关闭旧版自动交易')}
            </Button>
          </div>
        </Alert>
      ) : null}
      <section className="mt-6" aria-busy={statusLoading}>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-[15px] font-semibold">{t('平台总闸')}</h2>
          {status && <div className="flex flex-wrap gap-2 text-[12px] font-medium">
            <span className={status.emergencyStop ? 'text-down' : 'text-up'}>{status.emergencyStop ? t('紧急停止') : t('运行中')}</span>
            <span className="text-muted">{t('执行入口：Agent 平台')}</span>
          </div>}
        </div>
        {updatedAt && !Number.isNaN(updatedAt.getTime()) && (
          <p className="mt-2 text-[12px] text-muted">{t('最后更新时间')} {updatedAt.toLocaleString(getLanguage())}</p>
        )}
        <div className="mt-4 flex flex-wrap gap-2">
          <Button variant="danger" disabled={disabled || status?.emergencyStop !== false} onClick={() => setEmergencyOpen(true)}>{t('紧急停止')}</Button>
          <Button variant="ghost" disabled={disabled || status?.emergencyStop !== true} onClick={() => setResumeOpen(true)}>{t('恢复运行')}</Button>
        </div>
      </section>
      <AdminAgentPlatformSection onEmergencyTriggered={() => void loadStatus()} />
      <ConfirmDialog open={emergencyOpen} title={t('触发紧急停止')} content={t('确认后后端会切换到紧急停止状态。请先确认当前 OKX 没有未处理订单。')} confirmLabel={t('紧急停止')} cancelLabel={t('返回')} tone="danger" pending={submitting} onConfirm={() => void emergencyStop()} onClose={() => setEmergencyOpen(false)} />
      <ConfirmDialog open={resumeOpen} title={t('恢复系统运行')} content={t('确认恢复后，扫描和交易流程将重新按后端配置运行。')} confirmLabel={t('恢复运行')} cancelLabel={t('返回')} pending={submitting} onConfirm={() => void resume()} onClose={() => setResumeOpen(false)} />
      <ConfirmDialog open={autoDisableOpen} title={t('关闭旧版自动交易')} content={t('确认后将关闭旧版单账户全局自动交易，后续全部自动交易统一由「我的 Agent」执行。')} confirmLabel={t('确认关闭')} cancelLabel={t('返回')} pending={submitting} onConfirm={() => void disableAutoTrade()} onClose={() => setAutoDisableOpen(false)} />
    </PageShell>
  );
}

type AdminOverviewData = {
  totalAgents: number;
  runningAgents: number;
  activeUsers: number;
  emergencyStopped: boolean;
  wsSessions: Array<{ username: string; state: string; reason: string }>;
  agents: Array<{ id: number; userName: string; name: string; status: string; runMode: string; lastDecision?: string }>;
};

function AdminAgentPlatformSection({ onEmergencyTriggered }: { onEmergencyTriggered: () => void }) {
  const notify = useNotify();
  const [overview, setOverview] = useState<AdminOverviewData | null>(null);
  const [pausing, setPausing] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);

  async function loadOverview() {
    try {
      const data = (await quantApi.adminAgentOverview()) as AdminOverviewData;
      setOverview(data);
    } catch {
      // Non-critical admin widget.
    }
  }

  useEffect(() => {
    void loadOverview();
  }, []);

  async function pauseAll() {
    setPausing(true);
    try {
      const res = (await quantApi.adminPauseAllAgents()) as { pausedCount?: number; overview?: AdminOverviewData };
      if (res.overview) setOverview(res.overview);
      notify(`${t('已暂停全平台活跃 Agent：')}${res.pausedCount ?? 0}`, { type: 'warning' });
      onEmergencyTriggered();
    } catch (error) {
      notify(error instanceof Error ? error.message : t('全局暂停失败'), { type: 'error' });
    } finally {
      setPausing(false);
      setConfirmOpen(false);
    }
  }

  if (!overview) return null;

  return (
    <section className="mt-8 rounded-[22px] border border-line bg-raised p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-[15px] font-semibold text-ink">{t('Agent 平台总览与全局急停')}</h2>
          <p className="mt-0.5 text-[12px] text-muted">
            {t('实时监控所有注册用户的活跃交易 Agent 与私有 WebSocket 连接池。')}
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" onClick={() => void loadOverview()}>{t('刷新')}</Button>
          <Button
            variant="danger"
            size="sm"
            disabled={pausing || overview.runningAgents === 0}
            onClick={() => setConfirmOpen(true)}
          >
            {t('暂停全平台 Agent')}
          </Button>
        </div>
      </div>

      <div className="mt-4 grid grid-cols-3 gap-4 rounded-[16px] bg-canvas p-3.5 text-[13px]">
        <div>
          <div className="text-[11px] text-muted">{t('平台总 Agent')}</div>
          <div className="mt-0.5 font-semibold text-ink">{overview.totalAgents}</div>
        </div>
        <div>
          <div className="text-[11px] text-muted">{t('运行中 Agent')}</div>
          <div className="mt-0.5 font-semibold text-up">{overview.runningAgents}</div>
        </div>
        <div>
          <div className="text-[11px] text-muted">{t('活跃用户 / WS 会话')}</div>
          <div className="mt-0.5 font-semibold text-ink">{overview.activeUsers} / {overview.wsSessions.length}</div>
        </div>
      </div>

      {overview.wsSessions.length > 0 ? (
        <div className="mt-3 flex flex-wrap gap-2">
          {overview.wsSessions.map((ws) => (
            <span key={ws.username} className="rounded-full border border-line bg-canvas px-2.5 py-0.5 text-[11px] text-muted">
              {ws.username} · WS {ws.state}
            </span>
          ))}
        </div>
      ) : null}

      <ConfirmDialog
        open={confirmOpen}
        title={t('暂停全平台所有活跃 Agent')}
        content={t('确认后将立即暂停所有用户的 RUNNING 状态 Agent 并开启系统紧急停止。')}
        confirmLabel={t('确认暂停')}
        cancelLabel={t('返回')}
        tone="danger"
        pending={pausing}
        onConfirm={() => void pauseAll()}
        onClose={() => setConfirmOpen(false)}
      />
    </section>
  );
}
