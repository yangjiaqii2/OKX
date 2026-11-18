import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Box,
  Button,
  CircularProgress,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
  useMediaQuery,
} from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import { useTheme } from '@mui/material/styles';
import { formatLeverage, formatNumber, formatPrice, formatSide, formatStatus, formatUSDT } from '../formatters';

type PositionSnapshotTableProps = {
  positions: Record<string, unknown>[];
  closingKey?: string;
  onClosePosition?: (position: Record<string, unknown>) => void;
};

export function PositionSnapshotTable({ positions, closingKey, onClosePosition }: PositionSnapshotTableProps) {
  const theme = useTheme();
  const mobile = useMediaQuery(theme.breakpoints.down('md'));
  if (positions.length === 0) {
    return (
      <Typography color="text.secondary" variant="body2">
        暂无持仓。若 OKX 实际有仓位，请查看接口错误信息和 API 读取权限。
      </Typography>
    );
  }

  if (mobile) {
    return <Box sx={{ display: 'grid', gap: 1 }}>
      {positions.map((position) => {
        const key = positionKey(position);
        const unmanaged = position.ownership === 'UNMANAGED';
        return <Accordion key={key} disableGutters sx={{ border: `1px solid ${unmanaged ? '#FF3B30' : '#242824'}` }}>
          <AccordionSummary expandIcon={<ExpandMoreIcon />} sx={{ minHeight: 60, '& .MuiAccordionSummary-content': { my: 1 } }}>
            <Box sx={{ width: 1, display: 'grid', gridTemplateColumns: '1fr auto', gap: 1 }}>
              <Box><Typography fontWeight={900}>{String(position.instId ?? '-')}</Typography>
                <Typography variant="caption" color={unmanaged ? 'error' : 'text.secondary'}>{formatSide(position.posSide)} · {formatStatus(position.ownership)}</Typography></Box>
              <Box sx={{ textAlign: 'right' }}><Typography fontWeight={900}>{formatUSDT(position.unrealizedPnl)}</Typography>
                <Typography variant="caption" color="text.secondary">{formatNumber(position.size)}</Typography></Box>
            </Box>
          </AccordionSummary>
          <AccordionDetails sx={{ borderTop: '1px solid #242824' }}>
            <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1.25 }}>
              <MobileValue label="均价" value={formatPrice(position.avgPrice)} />
              <MobileValue label="保证金" value={formatUSDT(position.margin)} />
              <MobileValue label="模式" value={`${formatSide(position.marginMode)} ${formatLeverage(position.leverage)}`} />
              <MobileValue label="状态" value={formatStatus(position.mode)} />
            </Box>
            {onClosePosition && <Button fullWidth color="error" variant="outlined" sx={{ mt: 2, minHeight: 44 }}
              onClick={() => onClosePosition(position)} disabled={closingKey === key}>平仓</Button>}
          </AccordionDetails>
        </Accordion>;
      })}
    </Box>;
  }

  return (
    <TableContainer>
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>合约</TableCell>
            <TableCell>方向</TableCell>
            <TableCell>模式</TableCell>
            <TableCell align="right">数量</TableCell>
            <TableCell align="right">均价</TableCell>
            <TableCell align="right">真实保证金</TableCell>
            <TableCell align="right">未实现盈亏</TableCell>
            <TableCell>状态</TableCell>
            <TableCell>归属</TableCell>
            {onClosePosition && <TableCell align="right">操作</TableCell>}
          </TableRow>
        </TableHead>
        <TableBody>
          {positions.map((position) => {
            const key = positionKey(position);
            const closing = closingKey === key;
            return (
              <TableRow key={key}>
                <TableCell>{String(position.instId ?? '-')}</TableCell>
                <TableCell>{formatSide(position.posSide)}</TableCell>
                <TableCell>{`${formatSide(position.marginMode)} ${formatLeverage(position.leverage)}`}</TableCell>
                <TableCell align="right">{formatNumber(position.size)}</TableCell>
                <TableCell align="right">{formatPrice(position.avgPrice)}</TableCell>
                <TableCell align="right">{formatUSDT(position.margin)}</TableCell>
                <TableCell align="right">{formatUSDT(position.unrealizedPnl)}</TableCell>
                <TableCell>{formatStatus(position.mode)}</TableCell>
                <TableCell>
                  <Tooltip title={String(position.reconciliationReason ?? 'OWNERSHIP_UNKNOWN')}>
                    <Typography
                      component="span"
                      variant="caption"
                      color={position.ownership === 'UNMANAGED' ? 'error' : 'text.secondary'}
                      fontWeight={position.ownership === 'UNMANAGED' ? 700 : 500}
                    >
                      {formatStatus(position.ownership)}
                    </Typography>
                  </Tooltip>
                </TableCell>
                {onClosePosition && (
                  <TableCell align="right">
                    <Button
                      size="small"
                      color="error"
                      variant="outlined"
                      onClick={() => onClosePosition(position)}
                      disabled={closing}
                      startIcon={closing ? <CircularProgress size={12} color="inherit" /> : undefined}
                      sx={{ minWidth: 72 }}
                    >
                      {closing ? '提交中' : '平仓'}
                    </Button>
                  </TableCell>
                )}
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

function MobileValue({ label, value }: { label: string; value: string }) {
  return <Box><Typography variant="caption" color="text.secondary">{label}</Typography><Typography variant="body2" fontWeight={800}>{value}</Typography></Box>;
}

export function positionKey(position: Record<string, unknown>) {
  return `${String(position.instId ?? '-')}-${String(position.posSide ?? 'net')}`;
}
