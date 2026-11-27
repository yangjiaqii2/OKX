import { Activity } from 'lucide-react';
import { useEffect, useState } from 'react';
import { quantApi } from '../api/quantApi';
import { formatAutoTradeReason } from '../formatters';
import { t, useLanguage } from '../i18n';
import { cn } from '../lib/cn';

type RuntimeStatus = {
  tradingMode: string;
  okxRest: string;
  publicWebSocket: string;
  privateWebSocket: string;
  privateWebSocketReason?: string;
  strategyEngine: string;
  tradingEngine: string;
  tradingReason?: string;
  unknownOrders?: number;
  unmanagedPositions?: number;
  ledgerSync?: string;
  checkedAt?: string;
};

const unavailable: RuntimeStatus = {
  tradingMode: 'UNKNOWN',
  okxRest: 'UNKNOWN',
  publicWebSocket: 'UNKNOWN',
  privateWebSocket: 'UNKNOWN',
  strategyEngine: 'UNKNOWN',
  tradingEngine: 'UNKNOWN',
};

const tradableEngineStates = ['READY', 'MANUAL_CONFIRM', 'AUTO_ENABLED'];
const failedStates = ['ERROR', 'EMERGENCY_STOP', 'BLOCKED', 'FAILED'];
const limitedEngineStates = ['DISABLED', 'INITIALIZING', 'RECOVERING', 'DEGRADED', 'SHADOW_ONLY'];

export function RuntimeStatusBar() {
  useLanguage();
  const [status, setStatus] = useState<RuntimeStatus>(unavailable);
  const [open, setOpen] = useState(false);
  const needsAttention = (status.unknownOrders ?? 0) > 0
    || (status.unmanagedPositions ?? 0) > 0
    || status.okxRest === 'ERROR'
    || failedStates.includes(status.tradingEngine)
    || status.tradingEngine === 'DEGRADED';
  const healthy = !needsAttention && tradableEngineStates.includes(status.tradingEngine);
  const engineLabel = displayValue(status.tradingEngine);
  const summary = healthy ? t('运行正常') : needsAttention ? `${engineLabel} · ${t('运行异常')}` : engineLabel;

  useEffect(() => {
    let request: AbortController | undefined;
    const load = async () => {
      request?.abort();
      const controller = new AbortController();
      request = controller;
      try {
        const next = await quantApi.runtimeStatus(controller.signal) as RuntimeStatus;
        if (!controller.signal.aborted) setStatus(next);
      } catch {
        if (!controller.signal.aborted) setStatus(unavailable);
      }
    };
    void load();
    const timer = window.setInterval(() => void load(), 30_000);
    return () => {
      window.clearInterval(timer);
      request?.abort();
    };
  }, []);

  return (
    <div className="relative flex items-center gap-3">
      <span className={cn('hidden items-center gap-2 text-[13px] font-medium md:inline-flex', needsAttention ? 'text-warning' : 'text-muted')}>
        <i className={cn('size-1.5 rounded-full', needsAttention ? 'bg-warning' : healthy ? 'bg-up' : 'bg-current')} />
        {summary}
      </span>
      <span className={cn('text-[13px] font-medium', status.tradingMode === 'LIVE' ? 'text-down' : 'text-muted')}>
        {modeLabel(status.tradingMode)}
      </span>
      <button
        type="button"
        className="relative grid size-8 place-items-center rounded-lg text-muted hover:bg-sunken hover:text-ink"
        aria-label={t('运行状态')}
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        <Activity size={16} />
        {needsAttention ? <i className="absolute right-1.5 top-1.5 size-1.5 rounded-full bg-down" /> : null}
      </button>
      {open ? (
        <>
          <button type="button" className="fixed inset-0 z-30 cursor-default" aria-label={t('关闭')} onClick={() => setOpen(false)} />
          <div id="runtime-status-detail" className="absolute right-0 top-10 z-40 w-[320px] max-w-[calc(100vw-32px)] rounded-xl bg-raised p-4 text-left shadow-[0_1px_1px_rgba(16,20,28,0.05),0_16px_40px_rgba(16,20,28,0.12)]">
            <p className="mb-2 text-sm font-semibold">{t('运行状态')}</p>
            <div className="space-y-1.5">
              <RuntimeItem label="REST" value={status.okxRest} />
              <RuntimeItem label="PUB WS" value={status.publicWebSocket} />
              <RuntimeItem label="PRIV WS" value={status.privateWebSocket} reason={status.privateWebSocketReason} />
              <RuntimeItem label="STRAT" value={status.strategyEngine} />
              <RuntimeItem label="EXEC" value={status.tradingEngine} reason={status.tradingReason} />
              <RuntimeItem label="LEDGER" value={status.ledgerSync ?? 'UNKNOWN'} />
              {(status.unknownOrders ?? 0) > 0 ? <RuntimeItem label="UNKNOWN ORD" value={String(status.unknownOrders)} danger /> : null}
              {(status.unmanagedPositions ?? 0) > 0 ? <RuntimeItem label="UNMANAGED POS" value={String(status.unmanagedPositions)} danger /> : null}
            </div>
            {status.checkedAt ? <p className="mt-2 text-[12px] text-muted">{t('后端检查时间')} {status.checkedAt}</p> : null}
          </div>
        </>
      ) : null}
    </div>
  );
}

function RuntimeItem({ label, value, reason, danger = false }: { label: string; value: string; reason?: string; danger?: boolean }) {
  useLanguage();
  const bad = danger || failedStates.includes(value);
  const good = tradableEngineStates.includes(value) || ['CONNECTED', 'AUTO_RUNNING', 'RECENT_WINDOW_SYNCED'].includes(value);
  const warn = limitedEngineStates.includes(value) || ['NOT_IMPLEMENTED', 'UNBOUND', 'UNKNOWN', 'RECONNECTING', 'AUTO_DISABLED_BY_CONFIG', 'SHADOW_RUNNING'].includes(value);
  const color = bad ? 'text-down' : good ? 'text-up' : warn ? 'text-warning' : 'text-muted';
  return (
    <p className="flex items-baseline gap-2 text-[12px] leading-5 text-muted">
      <i className={cn('size-1.5 shrink-0 translate-y-[-1px] rounded-full bg-current', color)} />
      <span>{label} <span className={cn('font-semibold', color)}>{displayValue(value)}</span>{reason ? ` · ${formatAutoTradeReason(reason)}` : null}</span>
    </p>
  );
}

function modeLabel(value: string) {
  if (value === 'LIVE') return t('实盘交易');
  if (value === 'DEMO') return t('模拟交易');
  if (value === 'DISABLED') return t('交易禁用');
  return value.split('_').join(' ');
}

function displayValue(value: string) {
  const labels: Record<string, string> = {
    NOT_IMPLEMENTED: t('未接入'),
    READY: t('就绪'),
    MANUAL_CONFIRM: t('人工确认'),
    SHADOW_ONLY: t('Shadow 观察'),
    EMERGENCY_STOP: t('紧急停止'),
    ERROR: t('异常'),
    FAILED: t('失败'),
    MONITORING_ONLY: t('仅监控'),
    AUTO_RUNNING: t('运行中'),
    AUTO_ENABLED: t('已启用'),
    DISABLED: t('关闭'),
    CONNECTED: t('已连接'),
    DISCONNECTED: t('未连接'),
    RECOVERING: t('恢复中'),
    BLOCKED: t('已拦截'),
    UNKNOWN: t('未知'),
  };
  return labels[value] ?? value.split('_').join(' ');
}
