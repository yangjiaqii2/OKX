import {
  Alert,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  Typography,
} from '@mui/material';
import { formatNumber, formatSide, formatUSDT } from '../formatters';

type ClosePositionDialogProps = {
  open: boolean;
  position: Record<string, unknown> | null;
  loading: boolean;
  onCancel: () => void;
  onConfirm: () => void;
};

export function ClosePositionDialog({
  open,
  position,
  loading,
  onCancel,
  onConfirm,
}: ClosePositionDialogProps) {
  const instId = String(position?.instId ?? '-');
  const posSide = formatSide(position?.posSide);
  const marginMode = formatSide(position?.marginMode);

  return (
    <Dialog open={open} onClose={loading ? undefined : onCancel} fullWidth maxWidth="xs">
      <DialogTitle>确认平仓</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5}>
          <Alert severity="warning">
            将向 OKX 提交一键平仓请求，提交后请在 OKX 当前委托和持仓里确认最终成交。
          </Alert>
          <Stack spacing={0.75}>
            <ConfirmRow label="合约" value={instId} />
            <ConfirmRow label="方向" value={posSide} />
            <ConfirmRow label="保证金模式" value={marginMode} />
            <ConfirmRow label="数量" value={formatNumber(position?.size)} />
            <ConfirmRow label="真实保证金" value={formatUSDT(position?.margin)} />
            <ConfirmRow label="未实现盈亏" value={formatUSDT(position?.unrealizedPnl)} />
          </Stack>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onCancel} disabled={loading}>
          取消
        </Button>
        <Button
          color="error"
          variant="contained"
          onClick={onConfirm}
          disabled={loading || !position}
          startIcon={loading ? <CircularProgress size={14} color="inherit" /> : undefined}
        >
          {loading ? '提交中' : '确认平仓'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function ConfirmRow({ label, value }: { label: string; value: string }) {
  return (
    <Stack direction="row" justifyContent="space-between" alignItems="center" gap={2}>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="body2" fontWeight={800} sx={{ fontFamily: '"JetBrains Mono", monospace', textAlign: 'right' }}>
        {value}
      </Typography>
    </Stack>
  );
}
