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
import { Box, Typography } from '@mui/material';
import { AppBar, Layout, Menu } from 'react-admin';
import { RuntimeStatusBar } from './components/RuntimeStatusBar';
import { MobileBottomNavigation } from './components/MobileBottomNavigation';

const AppMenu = () => {
  return (
    <Box sx={{ px: 1, py: 1.25 }}>
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
};

const TradingAppBar = () => (
  <AppBar color="inherit" toolbar={false}>
    <Typography fontWeight={900} sx={{ display: { xs: 'none', lg: 'block' }, mr: 2, minWidth: 108, letterSpacing: '-0.02em' }}>QUANT DESK</Typography>
    <RuntimeStatusBar />
  </AppBar>
);

export const appIcons = {
  contract: StackedLineChartIcon,
  pending: PendingActionsIcon,
  autoTrade: ReceiptLongIcon,
  lifecycle: PlaylistAddCheckIcon,
  okxOrders: AssignmentTurnedInIcon,
};

export const AppLayout = (props: Parameters<typeof Layout>[0]) => (
  <>
    <Layout {...props} menu={AppMenu} appBar={TradingAppBar} appBarAlwaysOn />
    <MobileBottomNavigation />
  </>
);
