import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import type { ReactNode } from 'react';
import { formatAction, formatLeverage, formatNumber, formatPrice, formatSide } from '../formatters';

type PendingOrderReviewDialogProps = {
  open: boolean;
  record: Record<string, unknown>;
  submitting?: boolean;
  onClose: () => void;
  onConfirm: (marginAmount: string) => void;
};

export function PendingOrderReviewDialog({ open, record, submitting, onClose, onConfirm }: PendingOrderReviewDialogProps) {
  const [marginAmount, setMarginAmount] = useState('');
  const amountValid = Number(marginAmount) > 0;

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>
        <Stack direction="row" spacing={1} alignItems="center">
          <WarningAmberIcon color="warning" />
          <span>确认提交 OKX 实盘订单</span>
        </Stack>
      </DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          <TextField
            label="投入保证金金额 USDT"
            value={marginAmount}
            onChange={(event) => setMarginAmount(event.target.value)}
            type="number"
            size="small"
            inputProps={{ min: 0, step: '0.01' }}
            helperText="确认时按 金额 × 杠杆 ÷ 入场价 换算下单数量"
            fullWidth
          />
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' }, gap: 1 }}>
            <ReviewItem label="合约" value={String(record.instId ?? '-')} emphasis />
            <ReviewItem label="动作" value={formatAction(record.action)} emphasis />
            <ReviewItem label="方向" value={`${formatSide(record.side)} / ${formatSide(record.posSide)}`} />
            <ReviewItem label="订单类型" value={formatSide(record.orderType)} />
            <ReviewItem label="价格" value={formatPrice(record.price)} />
            <ReviewItem label="杠杆" value={formatLeverage(record.leverage)} />
            <ReviewItem label="止损" value={formatPrice(record.stopLossPrice)} />
            <ReviewItem label="止盈" value={formatPrice(record.takeProfitPrice)} />
            <ReviewItem label="风险收益比" value={formatNumber(record.riskRewardRatio, 2)} />
            <ReviewItem label="状态" value={String(record.status ?? '-')} />
          </Box>
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={submitting}>
          返回
        </Button>
        <Button variant="contained" color="warning" onClick={() => onConfirm(marginAmount)} disabled={submitting || !amountValid}>
          确认实盘提交
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function ReviewItem({ label, value, emphasis = false }: { label: string; value: ReactNode; emphasis?: boolean }) {
  return (
    <Box
      sx={{
        p: 1.25,
        borderRadius: 1,
        border: '1px solid rgba(148, 163, 184, 0.12)',
        bgcolor: emphasis ? 'rgba(245, 158, 11, 0.08)' : 'rgba(15, 23, 42, 0.62)',
      }}
    >
      <Typography variant="caption" color="text.secondary" fontWeight={800}>
        {label}
      </Typography>
      <Typography sx={{ mt: 0.5, fontFamily: '"JetBrains Mono", monospace', overflowWrap: 'anywhere' }} fontWeight={800}>
        {value}
      </Typography>
    </Box>
  );
}
