import { positionDirection } from '../displayData';
import { colors } from '../designTokens';
import { PanelState } from './PanelState';
import { panel } from './surfaces';
import { ScrollableTable } from './ScrollableTable';
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Box,
  Button,
  CircularProgress,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
  useMediaQuery,
} from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import { useTheme } from '@mui/material/styles';
import { formatLeverage, formatNumber, formatPrice, formatSide, formatStatus, formatUSDT, pnlColor } from '../formatters';
import { t, useLanguage } from '../i18n';

type PositionSnapshotTableProps = {
  positions: Record<string, unknown>[];
  closingKey?: string;
  onClosePosition?: (position: Record<string, unknown>) => void;
};

export function PositionSnapshotTable({ positions, closingKey, onClosePosition }: PositionSnapshotTableProps) {
  useLanguage();
  const theme = useTheme();
  const mobile = useMediaQuery(theme.breakpoints.down('md'));
  if (positions.length === 0) {
    return (
      <PanelState sx={panel}>{t("暂无持仓。若 OKX 实际有仓位，请查看接口错误信息和 API 读取权限。")}</PanelState>
    );
  }

  if (mobile) {
    return <Box sx={{ display: 'grid', gap: 1 }}>
      {positions.map((position) => {
        const key = positionKey(position);
        const unmanaged = position.ownership === 'UNMANAGED';
        return <Accordion key={key} disableGutters sx={{ ...panel, borderColor: unmanaged ? colors.errorBorder : 'divider', '&::before': { display: 'none' } }}>
          <AccordionSummary expandIcon={<ExpandMoreIcon />} sx={{ minHeight: 64, gap: 1, '& .MuiAccordionSummary-content': { my: 1, minWidth: 0 } }}>
            <Box sx={{ width: 1, display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, auto)', gap: 1 }}>
              <Box><Typography fontWeight={900}>{String(position.instId ?? '-')}</Typography>
                <Typography variant="caption" color={unmanaged ? 'error' : 'text.secondary'}>{formatSide(positionDirection(position))} · {formatStatus(position.ownership)}</Typography></Box>
              <Box sx={{ textAlign: 'right' }}><Typography fontWeight={750} color={pnlColor(position.unrealizedPnl)}>{formatUSDT(position.unrealizedPnl)}</Typography>
                <Typography variant="caption" color="text.secondary">{formatNumber(position.size)}</Typography></Box>
            </Box>
          </AccordionSummary>
          <AccordionDetails sx={{ borderTop: 1, borderColor: 'divider' }}>
            <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 1.5 }}>
              <MobileValue label={t("均价")} value={formatPrice(position.avgPrice)} />
              <MobileValue label={t("保证金")} value={formatUSDT(position.margin)} />
              <MobileValue label={t("模式")} value={`${formatSide(position.marginMode)} ${formatLeverage(position.leverage)}`} />
              <MobileValue label={t("状态")} value={formatStatus(position.mode)} />
            </Box>
            {onClosePosition && <Button fullWidth color="error" variant="outlined" sx={{ mt: 2, minHeight: 44 }}
              onClick={() => onClosePosition(position)} disabled={closingKey === key}>{t("平仓")}</Button>}
          </AccordionDetails>
        </Accordion>;
      })}
    </Box>;
  }

  return (
    <ScrollableTable label={t("持仓")}>
      <TableHead>
        <TableRow>
          <TableCell>{t("合约")}</TableCell>
          <TableCell>{t("方向")}</TableCell>
          <TableCell>{t("模式")}</TableCell>
          <TableCell align="right">{t("数量")}</TableCell>
          <TableCell align="right">{t("均价")}</TableCell>
          <TableCell align="right">{t("真实保证金")}</TableCell>
          <TableCell align="right">{t("未实现盈亏")}</TableCell>
          <TableCell>{t("状态")}</TableCell>
          <TableCell>{t("归属")}</TableCell>
          {onClosePosition && <TableCell align="right">{t("操作")}</TableCell>}
        </TableRow>
      </TableHead>
      <TableBody>
        {positions.map((position) => {
          const key = positionKey(position);
          const closing = closingKey === key;
          return (
            <TableRow key={key}>
              <TableCell>{String(position.instId ?? '-')}</TableCell>
              <TableCell>{formatSide(positionDirection(position))}</TableCell>
              <TableCell>{`${formatSide(position.marginMode)} ${formatLeverage(position.leverage)}`}</TableCell>
              <TableCell align="right">{formatNumber(position.size)}</TableCell>
              <TableCell align="right">{formatPrice(position.avgPrice)}</TableCell>
              <TableCell align="right">{formatUSDT(position.margin)}</TableCell>
              <TableCell align="right" sx={{ color: pnlColor(position.unrealizedPnl), fontWeight: 650 }}>{formatUSDT(position.unrealizedPnl)}</TableCell>
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
                    {closing ? t("提交中") : t("平仓")}
                  </Button>
                </TableCell>
              )}
            </TableRow>
          );
        })}
      </TableBody>
      </ScrollableTable>
  );
}

function MobileValue({ label, value }: { label: string; value: string }) {
  useLanguage();
  return <Box><Typography variant="caption" color="text.secondary">{label}</Typography><Typography variant="body2" fontWeight={800}>{value}</Typography></Box>;
}

export function positionKey(position: Record<string, unknown>) {
  return `${String(position.instId ?? '-')}-${String(position.posSide ?? 'net')}`;
}
