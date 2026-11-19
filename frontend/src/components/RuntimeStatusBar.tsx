import MonitorHeartOutlinedIcon from '@mui/icons-material/MonitorHeartOutlined';
import { Badge, Box, Chip, IconButton, Popover, Stack, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { quantApi } from '../api/quantApi';
import { elevation } from '../designTokens';

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

export function RuntimeStatusBar() {
  const [status, setStatus] = useState<RuntimeStatus>(unavailable);
  const [statusAnchor, setStatusAnchor] = useState<HTMLElement | null>(null);
  const needsAttention = (status.unknownOrders ?? 0) > 0
    || (status.unmanagedPositions ?? 0) > 0
    || ['ERROR', 'EMERGENCY_STOP', 'BLOCKED', 'FAILED'].includes(status.tradingEngine);

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
    <Stack direction="row" gap={{ xs: 0.5, md: 1 }} alignItems="center" minWidth={0} sx={{ flex: 1, justifyContent: 'flex-end' }}>
      <Chip
        size="small"
        color={status.tradingMode === 'LIVE' ? 'error' : status.tradingMode === 'DEMO' ? 'info' : 'default'}
        label={modeLabel(status.tradingMode)}
        variant={status.tradingMode === 'LIVE' ? 'outlined' : 'filled'}
      />
      <Box sx={{ display: { xs: 'none', md: 'block' } }}>
        <RuntimeItem label="EXEC" value={status.tradingEngine} />
      </Box>
      <IconButton aria-label="运行状态" aria-expanded={Boolean(statusAnchor)}
        aria-controls={statusAnchor ? 'runtime-status-detail' : undefined} onClick={(event) => setStatusAnchor(event.currentTarget)}>
        <Badge variant="dot" color="error" invisible={!needsAttention}>
          <MonitorHeartOutlinedIcon />
        </Badge>
      </IconButton>
      <Popover open={Boolean(statusAnchor)} anchorEl={statusAnchor} onClose={() => setStatusAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }} transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{ paper: { id: 'runtime-status-detail', sx: {
          mt: 1, p: 2, width: 336, maxWidth: 'calc(100vw - 32px)', border: 1, borderColor: 'divider',
          boxShadow: elevation.overlay, overflowWrap: 'anywhere',
        } } }}>
        <Stack spacing={1}>
          <Typography variant="h6">运行状态</Typography>
          <RuntimeItem label="REST" value={status.okxRest} />
          <RuntimeItem label="PUB WS" value={status.publicWebSocket} />
          <RuntimeItem label="PRIV WS" value={status.privateWebSocket} reason={status.privateWebSocketReason} />
          <RuntimeItem label="STRAT" value={status.strategyEngine} />
          <RuntimeItem label="EXEC" value={status.tradingEngine} reason={status.tradingReason} />
          <RuntimeItem label="LEDGER" value={status.ledgerSync ?? 'UNKNOWN'} />
          {(status.unknownOrders ?? 0) > 0 ? <RuntimeItem label="UNKNOWN ORD" value={String(status.unknownOrders)} danger /> : null}
          {(status.unmanagedPositions ?? 0) > 0 ? <RuntimeItem label="UNMANAGED POS" value={String(status.unmanagedPositions)} danger /> : null}
          {status.checkedAt ? <Typography variant="caption" color="text.secondary">后端检查时间 {status.checkedAt}</Typography> : null}
        </Stack>
      </Popover>
    </Stack>
  );
}

function RuntimeItem({ label, value, reason, danger = false }: { label: string; value: string; reason?: string; danger?: boolean }) {
  const color = danger || value === 'ERROR' || value === 'EMERGENCY_STOP' || value === 'BLOCKED' || value === 'FAILED'
    ? 'error.main'
    : value === 'CONNECTED' || value === 'AUTO_RUNNING' || value === 'AUTO_ENABLED' || value === 'READY' || value === 'RECENT_WINDOW_SYNCED'
    ? 'success.main'
      : value === 'NOT_IMPLEMENTED' || value === 'UNBOUND' || value === 'UNKNOWN' || value === 'DEGRADED' || value === 'RECONNECTING' || value === 'AUTO_DISABLED_BY_CONFIG' || value === 'SHADOW_RUNNING' || value === 'SHADOW_ONLY'
        ? 'warning.main'
        : 'text.secondary';
  return (
    <Stack direction="row" gap={1} alignItems="baseline">
      <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: color, flexShrink: 0 }} />
      <Typography variant="caption" color="text.secondary">
        {label} <Box component="span" fontWeight={700} sx={{ color }}>{displayValue(value)}</Box>
        {reason ? ` · ${reason}` : null}
      </Typography>
    </Stack>
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
