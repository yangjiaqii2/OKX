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
import { Box, Divider, Typography, useMediaQuery } from '@mui/material';
import type { Theme } from '@mui/material/styles';
import { AppBar, Layout, Menu, Sidebar } from 'react-admin';
import { RuntimeStatusBar } from './components/RuntimeStatusBar';
import { MobileBottomNavigation } from './components/MobileBottomNavigation';
import { LanguageSwitch } from './components/LanguageSwitch';
import { appBarHeight, mobileNavHeight, safeArea } from './designTokens';
import { t, useLanguage } from './i18n';

const AppMenu = () => {
  useLanguage();
  return (
    <Box sx={{ p: 1 }}>
      <Menu sx={{
        width: '100%',
        gap: 0.5,
        '& .RaMenuItemLink-root': {
          minHeight: 44,
          mx: 0.5,
          px: 1.5,
          borderRadius: 1,
          color: 'text.secondary',
          fontWeight: 600,
          '& .MuiListItemIcon-root': { minWidth: 36, color: 'inherit' },
          '&.RaMenuItemLink-active': { bgcolor: 'action.selected', color: 'primary.dark', fontWeight: 750 },
        },
      }}>
        <Menu.DashboardItem leftIcon={<DashboardIcon />} />
        <Menu.ResourceItem name="contractCandidates" />
        <Menu.Item to="/positions" primaryText={t("当前持仓")} leftIcon={<AccountBalanceWalletIcon />} />
        <Menu.ResourceItem name="pendingOrders" />
        <Menu.ResourceItem name="currentOkxOrders" />
        <Menu.ResourceItem name="autoTradeRecords" />
        <Menu.ResourceItem name="autoTradeLifecycle" />
        <Menu.ResourceItem name="closePositionRecords" />
        <Divider sx={{ my: 1 }} />
        <Menu.Item to="/account-risk" primaryText={t("账户与风控")} leftIcon={<AccountBalanceWalletIcon />} />
        <Menu.Item to="/account-binding" primaryText={t("账号绑定")} leftIcon={<KeyIcon />} />
        <Menu.Item to="/security" primaryText={t("账号安全")} leftIcon={<ManageAccountsIcon />} />
        <Menu.Item to="/system-control" primaryText={t("系统控制")} leftIcon={<EmergencyIcon />} />
      </Menu>
    </Box>
  );
};

const TradingAppBar = (props: Parameters<typeof AppBar>[0]) => (
  <AppBar {...props} color="inherit" toolbar={false} sx={{
    pt: safeArea.top,
    pl: safeArea.left,
    pr: safeArea.right,
    '& .RaAppBar-toolbar': { minHeight: appBarHeight, gap: { xs: 0.5, md: 1 }, px: { xs: 1.5, md: 2 } },
    '& .RaAppBar-menuButton': { display: { xs: 'none', md: 'inline-flex' } },
    '& .RaUserMenu-userButton': {
      display: 'block',
      maxWidth: { sm: 128, md: 192 },
      overflow: 'hidden',
      textOverflow: 'ellipsis',
      whiteSpace: 'nowrap',
      '& .MuiButton-startIcon': { display: 'inline-flex', verticalAlign: 'middle' },
    },
  }}>
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, minWidth: 0 }}>
      <Box sx={{ display: 'grid', placeItems: 'center', width: 32, height: 32, borderRadius: 1, bgcolor: 'primary.main', color: 'primary.contrastText', flexShrink: 0 }}>
        <StackedLineChartIcon fontSize="small" aria-hidden />
      </Box>
      <Typography fontWeight={750} sx={{ display: { xs: 'none', sm: 'block' }, fontSize: { sm: '0.875rem', md: '1rem' }, whiteSpace: 'nowrap' }}>Quant Desk</Typography>
    </Box>
    <RuntimeStatusBar />
    <LanguageSwitch />
  </AppBar>
);

const TradingSidebar = (props: Parameters<typeof Sidebar>[0]) => {
  const desktop = useMediaQuery((theme: Theme) => theme.breakpoints.up('md'));
  return desktop ? <Sidebar {...props} /> : null;
};

export const appIcons = {
  contract: StackedLineChartIcon,
  pending: PendingActionsIcon,
  autoTrade: ReceiptLongIcon,
  lifecycle: PlaylistAddCheckIcon,
  okxOrders: AssignmentTurnedInIcon,
};

export const AppLayout = (props: Parameters<typeof Layout>[0]) => (
  <>
    <Layout {...props} menu={AppMenu} appBar={TradingAppBar} sidebar={TradingSidebar} appBarAlwaysOn sx={{
      minWidth: 0,
      minHeight: '100dvh',
      '& .RaLayout-appFrame': {
        mt: `calc(${appBarHeight}px + ${safeArea.top})`,
        pl: safeArea.left,
        pr: safeArea.right,
      },
      '& .RaLayout-contentWithSidebar': { minWidth: 0 },
      '& .RaLayout-content': {
        minWidth: 0,
        px: 0,
        pb: { xs: `calc(${mobileNavHeight}px + ${safeArea.bottom})`, md: safeArea.bottom },
      },
      '& .RaSidebar-fixed': {
        height: `calc(100dvh - ${appBarHeight}px - ${safeArea.top})`,
        bgcolor: 'background.paper',
        borderRight: 1,
        borderColor: 'divider',
        overflowY: 'auto',
      },
      '& .RaSidebar-closed .RaMenuItemLink-root': { px: 1.5, mx: 0 },
    }} />
    <MobileBottomNavigation />
  </>
);
