import { positionDirection } from '../displayData';
import { PanelState } from './PanelState';
import { ScrollableTable } from './ScrollableTable';
import { TableBody, TableCell, TableHead, TableRow } from './ui/table';
import { Button } from './ui/button';
import { formatLeverage, formatNumber, formatPrice, formatSide, formatStatus, formatUSDT, pnlColor } from '../formatters';
import { t, useLanguage } from '../i18n';

type PositionSnapshotTableProps = {
  positions: Record<string, unknown>[];
  closingKey?: string;
  onClosePosition?: (position: Record<string, unknown>) => void;
};

export function PositionSnapshotTable({ positions, closingKey, onClosePosition }: PositionSnapshotTableProps) {
  useLanguage();
  if (positions.length === 0) {
    return <PanelState>{t('暂无持仓。若 OKX 实际有仓位，请查看接口错误信息和 API 读取权限。')}</PanelState>;
  }

  return (
    <>
      <div className="grid gap-3 md:hidden">
        {positions.map((position) => {
          const key = positionKey(position);
          const unmanaged = position.ownership === 'UNMANAGED';
          return (
            <details key={key} className={unmanaged ? 'rounded-lg bg-down-soft px-3 py-2' : 'border-t border-line py-2'} open={unmanaged}>
              <summary className="flex cursor-pointer list-none items-center justify-between gap-3">
                <span className="font-semibold">{String(position.instId ?? '-')}</span>
                <span className="tabular-nums font-medium" style={{ color: pnlColor(position.unrealizedPnl) }}>{formatUSDT(position.unrealizedPnl)}</span>
              </summary>
              <dl className="mt-2 grid grid-cols-2 gap-2 text-[13px]">
                <Fact label={t('方向')} value={formatSide(positionDirection(position))} />
                <Fact label={t('模式')} value={`${formatSide(position.marginMode)} ${formatLeverage(position.leverage)}`} />
                <Fact label={t('数量')} value={formatNumber(position.size)} />
                <Fact label={t('均价')} value={formatPrice(position.avgPrice)} />
                <Fact label={t('真实保证金')} value={formatUSDT(position.margin)} />
                <Fact label={t('归属')} value={formatStatus(position.ownership)} />
              </dl>
              {onClosePosition ? (
                <Button className="mt-3" variant="ghost" disabled={closingKey === key} onClick={() => onClosePosition(position)}>
                  {closingKey === key ? t('提交中') : t('平仓')}
                </Button>
              ) : null}
            </details>
          );
        })}
      </div>
      <div className="hidden md:block">
        <ScrollableTable label={t('持仓')}>
          <TableHead>
            <TableRow>
              <TableCell>{t('合约')}</TableCell>
              <TableCell>{t('方向')}</TableCell>
              <TableCell>{t('模式')}</TableCell>
              <TableCell align="right">{t('数量')}</TableCell>
              <TableCell align="right">{t('均价')}</TableCell>
              <TableCell align="right">{t('真实保证金')}</TableCell>
              <TableCell align="right">{t('未实现盈亏')}</TableCell>
              <TableCell>{t('状态')}</TableCell>
              <TableCell>{t('归属')}</TableCell>
              {onClosePosition ? <TableCell align="right">{t('操作')}</TableCell> : null}
            </TableRow>
          </TableHead>
          <TableBody>
            {positions.map((position) => {
              const key = positionKey(position);
              const closing = closingKey === key;
              return (
                <TableRow key={key}>
                  <TableCell className="font-semibold">{String(position.instId ?? '-')}</TableCell>
                  <TableCell>{formatSide(positionDirection(position))}</TableCell>
                  <TableCell>{`${formatSide(position.marginMode)} ${formatLeverage(position.leverage)}`}</TableCell>
                  <TableCell align="right">{formatNumber(position.size)}</TableCell>
                  <TableCell align="right">{formatPrice(position.avgPrice)}</TableCell>
                  <TableCell align="right">{formatUSDT(position.margin)}</TableCell>
                  <TableCell align="right" style={{ color: pnlColor(position.unrealizedPnl) }} className="font-medium">{formatUSDT(position.unrealizedPnl)}</TableCell>
                  <TableCell>{formatStatus(position.mode)}</TableCell>
                  <TableCell className={position.ownership === 'UNMANAGED' ? 'font-medium text-down' : 'text-muted'}>
                    <span title={String(position.reconciliationReason ?? 'OWNERSHIP_UNKNOWN')}>{formatStatus(position.ownership)}</span>
                  </TableCell>
                  {onClosePosition ? (
                    <TableCell align="right">
                      <Button variant="ghost" disabled={closing} onClick={() => onClosePosition(position)}>
                        {closing ? t('提交中') : t('平仓')}
                      </Button>
                    </TableCell>
                  ) : null}
                </TableRow>
              );
            })}
          </TableBody>
        </ScrollableTable>
      </div>
    </>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-[12px] text-muted">{label}</dt>
      <dd className="font-medium tabular-nums">{value}</dd>
    </div>
  );
}

export function positionKey(position: Record<string, unknown>) {
  return `${String(position.instId ?? '-')}-${String(position.posSide ?? 'net')}`;
}
