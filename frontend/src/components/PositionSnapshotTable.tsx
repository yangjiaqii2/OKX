import {
  Button,
  CircularProgress,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { formatLeverage, formatNumber, formatPrice, formatSide, formatStatus, formatUSDT } from '../formatters';

type PositionSnapshotTableProps = {
  positions: Record<string, unknown>[];
  closingKey?: string;
  onClosePosition?: (position: Record<string, unknown>) => void;
};

export function PositionSnapshotTable({ positions, closingKey, onClosePosition }: PositionSnapshotTableProps) {
  if (positions.length === 0) {
    return (
      <Typography color="text.secondary" variant="body2">
        暂无持仓。若 OKX 实际有仓位，请查看接口错误信息和 API 读取权限。
      </Typography>
    );
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

export function positionKey(position: Record<string, unknown>) {
  return `${String(position.instId ?? '-')}-${String(position.posSide ?? 'net')}`;
}
