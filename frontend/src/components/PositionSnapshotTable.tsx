import {
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { formatNumber, formatPrice, formatSide, formatStatus, formatUSDT } from '../formatters';

type PositionSnapshotTableProps = {
  positions: Record<string, unknown>[];
};

export function PositionSnapshotTable({ positions }: PositionSnapshotTableProps) {
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
            <TableCell align="right">数量</TableCell>
            <TableCell align="right">均价</TableCell>
            <TableCell align="right">未实现盈亏</TableCell>
            <TableCell>状态</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {positions.map((position) => (
            <TableRow key={`${String(position.instId)}-${String(position.posSide)}`}>
              <TableCell>{String(position.instId ?? '-')}</TableCell>
              <TableCell>{formatSide(position.posSide)}</TableCell>
              <TableCell align="right">{formatNumber(position.size)}</TableCell>
              <TableCell align="right">{formatPrice(position.avgPrice)}</TableCell>
              <TableCell align="right">{formatUSDT(position.unrealizedPnl)}</TableCell>
              <TableCell>{formatStatus(position.mode)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}
