import { Box, Chip, Stack, Tooltip, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { quantApi } from '../api/quantApi';

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
  okxRest: 'ERROR',
  publicWebSocket: 'UNKNOWN',
  privateWebSocket: 'UNKNOWN',
  strategyEngine: 'UNKNOWN',
  tradingEngine: 'UNKNOWN',
};

export function RuntimeStatusBar() {
  const [status, setStatus] = useState<RuntimeStatus>(unavailable);

  useEffect(() => {
    let active = true;
    const load = async () => {
      try {
        const next = await quantApi.runtimeStatus() as RuntimeStatus;
        if (active) setStatus(next);
      } catch {
        if (active) setStatus(unavailable);
      }
    };
    void load();
    const timer = window.setInterval(() => void load(), 30_000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, []);

  return (
    <Stack direction="row" spacing={1.2} alignItems="center" minWidth={0} sx={{ flex: 1, overflowX: 'auto', scrollbarWidth: 'none' }}>
      <Chip
        size="small"
        color={status.tradingMode === 'LIVE' ? 'error' : status.tradingMode === 'DEMO' ? 'info' : 'default'}
        label={modeLabel(status.tradingMode)}
        sx={{
          flexShrink: 0,
          fontFamily: 'monospace',
          ...(status.tradingMode === 'LIVE' ? { fontWeight: 800, border: '1px solid #ef4444' } : {}),
        }}
      />
      <RuntimeItem label="REST" value={status.okxRest} />
      <RuntimeItem label="PUB WS" value={status.publicWebSocket} />
      <RuntimeItem label="PRIV WS" value={status.privateWebSocket} reason={status.privateWebSocketReason} />
      <RuntimeItem label="STRAT" value={status.strategyEngine} />
      <RuntimeItem label="EXEC" value={status.tradingEngine} reason={status.tradingReason} />
      <RuntimeItem label="LEDGER" value={status.ledgerSync ?? 'UNKNOWN'} />
      {(status.unknownOrders ?? 0) > 0 ? <RuntimeItem label="UNKNOWN ORD" value={String(status.unknownOrders)} danger /> : null}
      {(status.unmanagedPositions ?? 0) > 0 ? <RuntimeItem label="UNMANAGED POS" value={String(status.unmanagedPositions)} danger /> : null}
      {status.checkedAt ? (
        <Tooltip title={`后端检查时间 ${status.checkedAt}`}>
          <Typography variant="caption" color="text.secondary" sx={{ flexShrink: 0, fontFamily: 'monospace' }}>
            {new Date(status.checkedAt).toLocaleTimeString('zh-CN', { hour12: false })}
          </Typography>
        </Tooltip>
      ) : null}
    </Stack>
  );
}

function RuntimeItem({ label, value, reason, danger = false }: { label: string; value: string; reason?: string; danger?: boolean }) {
  const color = danger || value === 'ERROR' || value === 'EMERGENCY_STOP' || value === 'BLOCKED' || value === 'FAILED'
    ? '#ef4444'
    : value === 'CONNECTED' || value === 'AUTO_RUNNING' || value === 'AUTO_ENABLED' || value === 'READY' || value === 'RECENT_WINDOW_SYNCED'
    ? '#22c55e'
      : value === 'NOT_IMPLEMENTED' || value === 'UNBOUND' || value === 'UNKNOWN' || value === 'DEGRADED' || value === 'RECONNECTING' || value === 'AUTO_DISABLED_BY_CONFIG' || value === 'SHADOW_RUNNING' || value === 'SHADOW_ONLY'
        ? '#f59e0b'
        : '#94a3b8';
  return (
    <Tooltip title={`${label}: ${value}${reason ? ` · ${reason}` : ''}`}>
      <Stack direction="row" spacing={0.6} alignItems="center" sx={{ flexShrink: 0 }}>
        <Box sx={{ width: 6, height: 6, bgcolor: color }} />
        <Typography variant="caption" sx={{ color: 'text.secondary', fontFamily: 'monospace', whiteSpace: 'nowrap' }}>
          {label} <Box component="span" sx={{ color: 'text.primary' }}>{displayValue(value)}</Box>
        </Typography>
      </Stack>
    </Tooltip>
  );
}

function modeLabel(value: string) {
  if (value === 'LIVE') return 'LIVE · REAL FUNDS';
  if (value === 'DEMO') return 'DEMO · SIMULATED';
  if (value === 'DISABLED') return 'TRADING DISABLED';
  return value.split('_').join(' ');
}

function displayValue(value: string) {
  const labels: Record<string, string> = {
    NOT_IMPLEMENTED: 'NOT BUILT',
    MONITORING_ONLY: 'MONITOR',
    AUTO_RUNNING: 'RUNNING',
    AUTO_ENABLED: 'ENABLED',
    DISABLED: 'OFF',
  };
  return labels[value] ?? value.split('_').join(' ');
}
