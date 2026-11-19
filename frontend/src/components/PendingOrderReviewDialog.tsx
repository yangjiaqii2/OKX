import { colors } from '../designTokens';
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
  tradingMode: string;
  onClose: () => void;
  onConfirm: (marginAmount: string) => void;
};

export function PendingOrderReviewDialog({ open, record, submitting, tradingMode, onClose, onConfirm }: PendingOrderReviewDialogProps) {
  const [marginAmount, setMarginAmount] = useState('');
  const amountValid = Number(marginAmount) > 0;
  const notional = amountValid ? Number(marginAmount) * Number(record.leverage ?? 1) : 0;

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>
        <Stack direction="row" spacing={1} alignItems="center">
          <WarningAmberIcon color="warning" />
          <span>{tradingMode === 'LIVE' ? '确认提交 OKX LIVE 真实资金订单' : '确认提交 OKX DEMO 模拟订单'}</span>
        </Stack>
      </DialogTitle>
      <DialogContent>
        <Stack spacing={2}>
          <TextField
            label="本次投入保证金 USDT"
            value={marginAmount}
            onChange={(event) => setMarginAmount(event.target.value)}
            type="number"
            size="small"
            inputProps={{ inputMode: 'decimal', min: 0, step: '0.01' }}
            helperText="这里输入的是保证金，不是开仓价；确认时按 保证金 × 杠杆 ÷ 计划入场价 换算下单数量"
            fullWidth
          />
          {amountValid ? (
            <Alert severity="warning">
              预计名义仓位约 {formatNumber(notional, 2)} USDT。提交成功只代表OKX已接收委托，成交、账单和持仓以OKX为准。
            </Alert>
          ) : null}
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))' }, gap: 1 }}>
            <ReviewItem label="合约" value={String(record.instId ?? '-')} emphasis />
            <ReviewItem label="动作" value={formatAction(record.action)} emphasis />
            <ReviewItem label="方向" value={`${formatSide(record.side)} / ${formatSide(record.posSide)}`} />
            <ReviewItem label="订单类型" value={formatSide(record.orderType)} />
            <ReviewItem label="计划入场价" value={formatPrice(record.price)} />
            <ReviewItem label="杠杆" value={formatLeverage(record.leverage)} />
            <ReviewItem label="止损" value={formatPrice(record.stopLossPrice)} />
            <ReviewItem label="止盈" value={formatPrice(record.takeProfitPrice)} />
            <ReviewItem label="风险收益比" value={formatNumber(record.riskRewardRatio, 2)} />
            <ReviewItem label="信号评分" value={formatNumber(record.signalScore, 0)} />
            <ReviewItem label="资金费率" value={formatNumber(record.fundingRate, 4)} />
            <ReviewItem label="波动率" value={formatNumber(record.volatility, 4)} />
            <ReviewItem label="24h成交量" value={formatNumber(record.volume24h, 0)} />
            <ReviewItem label="状态" value={String(record.status ?? '-')} />
          </Box>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={submitting}>
          返回
        </Button>
        <Button variant="contained" color={tradingMode === 'LIVE' ? 'error' : 'info'} onClick={() => onConfirm(marginAmount)} disabled={submitting || !amountValid || tradingMode === 'DISABLED'}>
          {tradingMode === 'LIVE' ? '确认 LIVE 真实资金提交' : '确认 DEMO 模拟提交'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function ReviewItem({ label, value, emphasis = false }: { label: string; value: ReactNode; emphasis?: boolean }) {
  return (
    <Box
      sx={{
        p: 1.5,
        borderRadius: 1,
        border: 1, borderColor: 'divider',
        bgcolor: emphasis ? colors.warningSoft : colors.subtle,
      }}
    >
      <Typography variant="caption" color="text.secondary" fontWeight={800}>
        {label}
      </Typography>
      <Typography sx={{ mt: 0.5, overflowWrap: 'anywhere' }} fontWeight={800}>
        {value}
      </Typography>
    </Box>
  );
}
