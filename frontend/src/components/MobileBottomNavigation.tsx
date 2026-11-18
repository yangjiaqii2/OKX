import DashboardIcon from '@mui/icons-material/Dashboard';
import EmergencyIcon from '@mui/icons-material/Emergency';
import MoreHorizIcon from '@mui/icons-material/MoreHoriz';
import PendingActionsIcon from '@mui/icons-material/PendingActions';
import StackedLineChartIcon from '@mui/icons-material/StackedLineChart';
import WalletIcon from '@mui/icons-material/Wallet';
import { BottomNavigation, BottomNavigationAction, Box, Drawer, List, ListItemButton, ListItemText } from '@mui/material';
import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

const main = [
  { label: '总览', path: '/', icon: <DashboardIcon /> },
  { label: '机会', path: '/contractCandidates', icon: <StackedLineChartIcon /> },
  { label: '订单', path: '/currentOkxOrders', icon: <PendingActionsIcon /> },
  { label: '持仓', path: '/positions', icon: <WalletIcon /> },
];

const more = [
  ['待确认订单', '/pendingOrders'], ['交易复盘', '/autoTradeRecords'], ['生命周期', '/autoTradeLifecycle'],
  ['账户与风控', '/account-risk'], ['账号绑定', '/account-binding'], ['系统控制', '/system-control'],
];

export function MobileBottomNavigation() {
  const location = useLocation();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const value = main.find((item) => item.path === location.pathname)?.path ?? 'more';
  return (
    <>
      <Box sx={{ display: { xs: 'block', md: 'none' }, position: 'fixed', zIndex: 1300, left: 0, right: 0, bottom: 0,
        bgcolor: '#080A08', borderTop: '1px solid #242824', pb: 'env(safe-area-inset-bottom)' }}>
        <BottomNavigation value={value} showLabels sx={{ height: 64, bgcolor: 'transparent' }}>
          {main.map((item) => <BottomNavigationAction key={item.path} value={item.path} label={item.label}
            icon={item.icon} onClick={() => navigate(item.path)} />)}
          <BottomNavigationAction value="more" label="更多" icon={<MoreHorizIcon />} onClick={() => setOpen(true)} />
        </BottomNavigation>
      </Box>
      <Drawer anchor="bottom" open={open} onClose={() => setOpen(false)} PaperProps={{ sx: { pb: 'env(safe-area-inset-bottom)' } }}>
        <Box sx={{ p: 1.5, borderBottom: '1px solid #242824', display: 'flex', gap: 1, alignItems: 'center' }}>
          <EmergencyIcon color="warning" /><strong>更多功能</strong>
        </Box>
        <List>
          {more.map(([label, path]) => <ListItemButton key={path} sx={{ minHeight: 48 }} onClick={() => { setOpen(false); navigate(path); }}>
            <ListItemText primary={label} /></ListItemButton>)}
        </List>
      </Drawer>
    </>
  );
}
