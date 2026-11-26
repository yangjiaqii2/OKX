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
import { formatNumber, formatSide, formatUSDT, pnlColor } from '../formatters';
import { t, useLanguage } from '../i18n';

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
  useLanguage();
  const instId = String(position?.instId ?? '-');
  const posSide = formatSide(position?.posSide);
  const marginMode = formatSide(position?.marginMode);

  return (
    <Dialog open={open} onClose={loading ? undefined : onCancel} fullWidth maxWidth="xs">
      <DialogTitle>{t("确认平仓")}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5}>
          <Alert severity="warning">
            {t("将向 OKX 提交一键平仓请求，提交后请在 OKX 当前委托和持仓里确认最终成交。")}
          </Alert>
          <Stack spacing={1}>
            <ConfirmRow label={t("合约")} value={instId} />
            <ConfirmRow label={t("方向")} value={posSide} />
            <ConfirmRow label={t("保证金模式")} value={marginMode} />
            <ConfirmRow label={t("数量")} value={formatNumber(position?.size)} />
            <ConfirmRow label={t("真实保证金")} value={formatUSDT(position?.margin)} />
            <ConfirmRow label={t("未实现盈亏")} value={formatUSDT(position?.unrealizedPnl)} color={pnlColor(position?.unrealizedPnl)} />
          </Stack>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onCancel} disabled={loading}>
          {t("取消")}
        </Button>
        <Button
          color="error"
          variant="contained"
          onClick={onConfirm}
          disabled={loading || !position}
          startIcon={loading ? <CircularProgress size={14} color="inherit" /> : undefined}
        >
          {loading ? t("提交中") : t("确认平仓")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function ConfirmRow({ label, value, color }: { label: string; value: string; color?: string }) {
  useLanguage();
  return (
    <Stack direction="row" justifyContent="space-between" alignItems="baseline" gap={2} sx={{ py: 0.5, borderBottom: 1, borderColor: 'divider' }}>
      <Typography variant="body2" color="text.secondary" sx={{ flexShrink: 0 }}>
        {label}
      </Typography>
      <Typography variant="body2" color={color} fontWeight={700} sx={{ textAlign: 'right' }}>
        {value}
      </Typography>
    </Stack>
  );
}
