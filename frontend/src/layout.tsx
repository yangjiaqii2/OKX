import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet';
import AssignmentTurnedInIcon from '@mui/icons-material/AssignmentTurnedIn';
import DashboardIcon from '@mui/icons-material/Dashboard';
import EmergencyIcon from '@mui/icons-material/Emergency';
import KeyIcon from '@mui/icons-material/Key';
import ManageAccountsIcon from '@mui/icons-material/ManageAccounts';
import PlaylistAddCheckIcon from '@mui/icons-material/PlaylistAddCheck';
import PendingActionsIcon from '@mui/icons-material/PendingActions';
import ReceiptLongIcon from '@mui/icons-material/ReceiptLong';
import StackedLineChartIcon from '@mui/icons-material/StackedLineChart';
import { Box, Divider, Stack, Typography } from '@mui/material';
import { Layout, Menu } from 'react-admin';

const AppMenu = () => (
  <Box sx={{ px: 1, py: 1.25 }}>
    <Stack
      spacing={0.25}
      sx={{
        mx: 0.5,
        mb: 1,
        px: 1.25,
        py: 1.1,
        borderRadius: 1.5,
        border: '1px solid rgba(148, 163, 184, 0.14)',
        background: '#111827',
      }}
    >
      <Typography fontWeight={950} sx={{ letterSpacing: 0, lineHeight: 1.05 }}>
        Quant Desk
      </Typography>
      <Typography variant="caption" color="text.secondary" fontWeight={700}>
        Risk-first trading console
      </Typography>
    </Stack>
    <Divider sx={{ mx: 0.5, mb: 1.1 }} />
    <Menu>
      <Menu.DashboardItem leftIcon={<DashboardIcon />} />
      <Menu.ResourceItem name="contractCandidates" />
      <Menu.ResourceItem name="pendingOrders" />
      <Menu.ResourceItem name="autoTradeRecords" />
      <Menu.ResourceItem name="autoTradeLifecycle" />
      <Menu.ResourceItem name="currentOkxOrders" />
      <Menu.ResourceItem name="closePositionRecords" />
      <Menu.Item to="/account-risk" primaryText="账户与风控" leftIcon={<AccountBalanceWalletIcon />} />
      <Menu.Item to="/account-binding" primaryText="账号绑定" leftIcon={<KeyIcon />} />
      <Menu.Item to="/security" primaryText="账号安全" leftIcon={<ManageAccountsIcon />} />
      <Menu.Item to="/system-control" primaryText="系统控制" leftIcon={<EmergencyIcon />} />
    </Menu>
  </Box>
);

export const appIcons = {
  contract: StackedLineChartIcon,
  pending: PendingActionsIcon,
  autoTrade: ReceiptLongIcon,
  lifecycle: PlaylistAddCheckIcon,
  okxOrders: AssignmentTurnedInIcon,
};

export const AppLayout = (props: Parameters<typeof Layout>[0]) => <Layout {...props} menu={AppMenu} />;
