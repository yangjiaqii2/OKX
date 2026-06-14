import RefreshIcon from '@mui/icons-material/Refresh';
import SearchIcon from '@mui/icons-material/Search';
import { Chip, Stack } from '@mui/material';
import {
  Button,
  Datagrid,
  FunctionField,
  List,
  NumberField,
  SearchInput,
  TextField,
  TopToolbar,
  useNotify,
  useRefresh,
} from 'react-admin';
import { apiBaseUrl } from '../api/http';
import { quantApi } from '../api/quantApi';
import { formatPercent } from '../formatters';

const stockFilters = [<SearchInput key="q" source="q" alwaysOn placeholder="搜索代码、名称、板块" />];

const StockActions = () => {
  const notify = useNotify();
  const refresh = useRefresh();

  async function scan() {
    try {
      await quantApi.scanStocks();
      notify('A股扫描完成', { type: 'success' });
      refresh();
    } catch (error) {
      notify(error instanceof Error ? error.message : 'A股扫描失败', { type: 'error' });
    }
  }

  return (
    <TopToolbar>
      <Button label="刷新扫描" onClick={() => void scan()}>
        <RefreshIcon />
      </Button>
    </TopToolbar>
  );
};

export function StockCandidateList() {
  return (
    <List
      title="A股机会池"
      actions={<StockActions />}
      filters={stockFilters}
      sort={{ field: 'changePercent', order: 'DESC' }}
      perPage={25}
      exporter={false}
    >
      <Datagrid bulkActionButtons={false} rowClick={false}>
        <TextField source="symbol" label="代码" sortable />
        <TextField source="name" label="名称" sortable />
        <TextField source="sector" label="板块" sortable />
        <NumberField source="price" label="价格" sortable />
        <FunctionField
          label="涨跌幅"
          sortBy="changePercent"
          render={(record: Record<string, unknown>) => formatPercent(record.changePercent)}
        />
        <NumberField source="volumeRatio" label="量比" sortable />
        <FunctionField
          label="入选理由"
          render={(record: Record<string, unknown>) => (
            <Stack direction="row" gap={0.75} flexWrap="wrap">
              {((record.candidateReasonList as string[]) ?? []).slice(0, 2).map((item) => (
                <Chip key={item} size="small" label={item} variant="outlined" />
              ))}
            </Stack>
          )}
        />
        <FunctionField
          label="操作"
          render={(record: Record<string, unknown>) => (
            <Button
              label="查看报告"
              onClick={() => {
                window.open(`${apiBaseUrl}/stock/report?symbol=${encodeURIComponent(String(record.symbol))}`, '_blank');
              }}
            >
              <SearchIcon />
            </Button>
          )}
        />
      </Datagrid>
    </List>
  );
}
