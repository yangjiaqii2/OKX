import CancelIcon from '@mui/icons-material/Cancel';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import { Alert, Stack } from '@mui/material';
import {
  Button,
  Confirm,
  Datagrid,
  FunctionField,
  List,
  NumberField,
  SearchInput,
  TextField,
  useNotify,
  useRefresh,
} from 'react-admin';
import { useEffect, useState } from 'react';
import { quantApi } from '../api/quantApi';
import { PendingOrderReviewDialog } from '../components/PendingOrderReviewDialog';
import { StatusChip } from '../components/StatusChip';
import { TradingStatusStrip } from '../components/TradingStatusStrip';
import { formatAction, formatSide } from '../formatters';

const pendingOrderFilters = [<SearchInput key="q" source="q" alwaysOn placeholder="搜索合约、方向、状态" />];

const PendingOrderActions = ({ record }: { record: Record<string, unknown> }) => {
  const notify = useNotify();
  const refresh = useRefresh();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const id = String(record.id);

  async function confirmOrder(marginAmount: string) {
    setSubmitting(true);
    try {
      await quantApi.confirmOrder(id, marginAmount);
      notify('订单已确认，已提交OKX实盘接口', { type: 'success' });
      refresh();
    } catch (error) {
      notify(error instanceof Error ? error.message : '确认订单失败', { type: 'error' });
    } finally {
      setSubmitting(false);
      setConfirmOpen(false);
    }
  }

  async function cancelOrder() {
    setSubmitting(true);
    try {
      await quantApi.cancelOrder(id);
      notify('订单已取消', { type: 'success' });
      refresh();
    } catch (error) {
      notify(error instanceof Error ? error.message : '取消订单失败', { type: 'error' });
    } finally {
      setSubmitting(false);
      setCancelOpen(false);
    }
  }

  return (
    <Stack direction="row" gap={1}>
      <Button label="实盘确认" onClick={() => setConfirmOpen(true)}>
        <CheckCircleIcon />
      </Button>
      <Button label="取消" onClick={() => setCancelOpen(true)} color="warning">
        <CancelIcon />
      </Button>
      <PendingOrderReviewDialog
        open={confirmOpen}
        record={record}
        submitting={submitting}
        onConfirm={(marginAmount) => void confirmOrder(marginAmount)}
        onClose={() => setConfirmOpen(false)}
      />
      <Confirm
        isOpen={cancelOpen}
        title="取消待确认订单"
        content={`取消 ${String(record.instId)} 的待确认订单。`}
        confirm="取消订单"
        cancel="返回"
        onConfirm={() => void cancelOrder()}
        onClose={() => setCancelOpen(false)}
      />
    </Stack>
  );
};

const PendingOrderTradingStatus = () => {
  const [state, setState] = useState<{
    risk?: Record<string, unknown>;
    account?: Record<string, unknown>;
    pendingCount: number;
  }>({ pendingCount: 0 });

  useEffect(() => {
    let active = true;
    async function load() {
      try {
        const [risk, account, orders] = await Promise.all([
          quantApi.riskStatus(),
          quantApi.accountSummary(),
          quantApi.pendingOrders(),
        ]);
        if (active) {
          setState({
            risk: risk as Record<string, unknown>,
            account: account as Record<string, unknown>,
            pendingCount: Array.isArray(orders) ? orders.length : 0,
          });
        }
      } catch {
        if (active) {
          setState({ pendingCount: 0 });
        }
      }
    }
    void load();
    return () => {
      active = false;
    };
  }, []);

  return (
    <Stack spacing={1.5} sx={{ mb: 2 }}>
      <TradingStatusStrip risk={state.risk} account={state.account} pendingCount={state.pendingCount} />
    </Stack>
  );
};

export function PendingOrderList() {
  return (
    <List
      title="待确认订单"
      filters={pendingOrderFilters}
      sort={{ field: 'createdAt', order: 'DESC' }}
      perPage={25}
      exporter={false}
    >
      <>
        <PendingOrderTradingStatus />
        <Datagrid bulkActionButtons={false} rowClick={false}>
          <TextField source="instId" label="合约" sortable />
          <FunctionField label="动作" sortBy="action" render={(record: Record<string, unknown>) => formatAction(record.action)} />
          <FunctionField
            label="方向"
            sortBy="side"
            render={(record: Record<string, unknown>) => `${formatSide(record.side)} / ${formatSide(record.posSide)}`}
          />
          <NumberField source="price" label="价格" sortable />
          <NumberField source="leverage" label="杠杆" sortable />
          <NumberField source="stopLossPrice" label="止损" sortable />
          <NumberField source="takeProfitPrice" label="止盈" sortable />
          <FunctionField
            label="状态"
            sortBy="status"
            render={(record: Record<string, unknown>) => <StatusChip value={String(record.status ?? '-')} />}
          />
          <FunctionField
            label="操作"
            render={(record: Record<string, unknown>) => <PendingOrderActions record={record} />}
          />
        </Datagrid>
      </>
    </List>
  );
}
