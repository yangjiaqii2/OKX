import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet';
import DashboardIcon from '@mui/icons-material/Dashboard';
import EmergencyIcon from '@mui/icons-material/Emergency';
import KeyIcon from '@mui/icons-material/Key';
import PendingActionsIcon from '@mui/icons-material/PendingActions';
import StackedLineChartIcon from '@mui/icons-material/StackedLineChart';
import { Box, Stack, Typography } from '@mui/material';
import { Layout, Menu } from 'react-admin';

const AppMenu = () => (
  <Box sx={{ px: 1.25, py: 1.5 }}>
    <Stack
      spacing={0.25}
      sx={{
        mx: 0.75,
        mb: 1.25,
        px: 1.5,
        py: 1.25,
        borderRadius: 2,
        border: '1px solid rgba(0, 212, 170, 0.18)',
        background: 'linear-gradient(145deg, rgba(0, 212, 170, 0.12), rgba(8, 13, 24, 0.46))',
      }}
    >
      <Typography fontWeight={950} sx={{ letterSpacing: 0, lineHeight: 1 }}>
        Quant Desk
      </Typography>
      <Typography variant="caption" color="text.secondary" fontWeight={700}>
        OKX live console
      </Typography>
    </Stack>
    <Menu>
      <Menu.DashboardItem leftIcon={<DashboardIcon />} />
      <Menu.ResourceItem name="contractCandidates" />
      <Menu.ResourceItem name="pendingOrders" />
      <Menu.Item to="/account-risk" primaryText="账户与风控" leftIcon={<AccountBalanceWalletIcon />} />
      <Menu.Item to="/account-binding" primaryText="账号绑定" leftIcon={<KeyIcon />} />
      <Menu.Item to="/system-control" primaryText="系统控制" leftIcon={<EmergencyIcon />} />
    </Menu>
  </Box>
);

export const appIcons = {
  contract: StackedLineChartIcon,
  pending: PendingActionsIcon,
};

export const AppLayout = (props: Parameters<typeof Layout>[0]) => <Layout {...props} menu={AppMenu} />;
