import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet';
import DashboardIcon from '@mui/icons-material/Dashboard';
import EmergencyIcon from '@mui/icons-material/Emergency';
import KeyIcon from '@mui/icons-material/Key';
import PendingActionsIcon from '@mui/icons-material/PendingActions';
import StackedLineChartIcon from '@mui/icons-material/StackedLineChart';
import { Layout, Menu } from 'react-admin';

const AppMenu = () => (
  <Menu>
    <Menu.DashboardItem leftIcon={<DashboardIcon />} />
    <Menu.ResourceItem name="contractCandidates" />
    <Menu.ResourceItem name="pendingOrders" />
    <Menu.Item to="/account-risk" primaryText="账户与风控" leftIcon={<AccountBalanceWalletIcon />} />
    <Menu.Item to="/account-binding" primaryText="账号绑定" leftIcon={<KeyIcon />} />
    <Menu.Item to="/system-control" primaryText="系统控制" leftIcon={<EmergencyIcon />} />
  </Menu>
);

export const appIcons = {
  contract: StackedLineChartIcon,
  pending: PendingActionsIcon,
};

export const AppLayout = (props: Parameters<typeof Layout>[0]) => <Layout {...props} menu={AppMenu} />;
