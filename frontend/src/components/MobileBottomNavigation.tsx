import DashboardIcon from '@mui/icons-material/Dashboard';
import MoreHorizIcon from '@mui/icons-material/MoreHoriz';
import PendingActionsIcon from '@mui/icons-material/PendingActions';
import StackedLineChartIcon from '@mui/icons-material/StackedLineChart';
import WalletIcon from '@mui/icons-material/Wallet';
import { BottomNavigation, BottomNavigationAction, Box, Drawer, List, ListItemButton, ListItemText, Typography } from '@mui/material';
import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { elevation, mobileNavHeight, radius, safeArea } from '../designTokens';
import { t, useLanguage } from '../i18n';

const main = [
  { label: '总览', path: '/', icon: <DashboardIcon /> },
  { label: '机会', path: '/contractCandidates', icon: <StackedLineChartIcon /> },
  { label: '订单', path: '/currentOkxOrders', icon: <PendingActionsIcon /> },
  { label: '持仓', path: '/positions', icon: <WalletIcon /> },
];

const more = [
  ['待确认订单', '/pendingOrders'], ['交易复盘', '/autoTradeRecords'], ['生命周期', '/autoTradeLifecycle'],
  ['平仓记录', '/closePositionRecords'], ['账户与风控', '/account-risk'], ['账号绑定', '/account-binding'],
  ['账号安全', '/security'], ['系统控制', '/system-control'],
];

export function MobileBottomNavigation() {
  useLanguage();
  const location = useLocation();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const value = main.find((item) => item.path === location.pathname)?.path ?? 'more';
  return (
    <>
      <Box component="nav" aria-label={t("主导航")} sx={{
        display: { xs: 'block', md: 'none' }, position: 'fixed', zIndex: (theme) => theme.zIndex.appBar,
        inset: 'auto 0 0', bgcolor: 'background.paper', borderTop: 1, borderColor: 'divider',
        pb: safeArea.bottom, pl: safeArea.left, pr: safeArea.right,
      }}>
        <BottomNavigation value={value} showLabels sx={{
          height: mobileNavHeight,
          '& .MuiBottomNavigationAction-root': { minWidth: 44, px: 0.5, pt: 0, gap: 0.5 },
          '& .MuiBottomNavigationAction-label, & .MuiBottomNavigationAction-label.Mui-selected': { fontSize: '0.75rem' },
        }}>
          {main.map((item) => <BottomNavigationAction key={item.path} value={item.path} label={t(item.label)}
            icon={item.icon} aria-current={value === item.path ? 'page' : undefined} onClick={() => navigate(item.path)} />)}
          <BottomNavigationAction value="more" label={t("更多")} icon={<MoreHorizIcon />}
            aria-expanded={open} aria-controls={open ? 'mobile-more-navigation' : undefined} onClick={() => setOpen(true)} />
        </BottomNavigation>
      </Box>
      <Drawer anchor="bottom" open={open} onClose={() => setOpen(false)} PaperProps={{ id: 'mobile-more-navigation', sx: {
        maxHeight: `calc(100dvh - ${safeArea.top} - 16px)`,
        borderTopLeftRadius: radius, borderTopRightRadius: radius, boxShadow: elevation.overlay,
        pb: safeArea.bottom, pl: safeArea.left, pr: safeArea.right,
      } }}>
        <Typography variant="h6" sx={{ p: 2, borderBottom: 1, borderColor: 'divider' }}>{t("更多功能")}</Typography>
        <List component="nav" aria-label={t("更多功能")}>
          {more.map(([label, path]) => <ListItemButton key={path} selected={location.pathname === path}
            aria-current={location.pathname === path ? 'page' : undefined}
            sx={{ minHeight: 48 }} onClick={() => { setOpen(false); navigate(path); }}>
            <ListItemText primary={t(label)} /></ListItemButton>)}
        </List>
      </Drawer>
    </>
  );
}
