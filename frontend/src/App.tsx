import { Box, CircularProgress } from '@mui/material';
import { lazy, Suspense } from 'react';
import { Admin, CustomRoutes, Resource } from 'react-admin';
import { Route } from 'react-router-dom';
import { quantDataProvider } from './api/quantDataProvider';
import { authProvider } from './api/authProvider';
import { AppLayout, appIcons } from './layout';
import { Dashboard } from './pages/Dashboard';
import { LoginPage } from './pages/LoginPage';
import { theme } from './theme';

const AccountBindingPage = lazy(() => import('./pages/AccountBindingPage').then((module) => ({ default: module.AccountBindingPage })));
const AccountRiskPage = lazy(() => import('./pages/AccountRiskPage').then((module) => ({ default: module.AccountRiskPage })));
const AutoTradeLifecycleList = lazy(() => import('./pages/AutoTradeLifecycleList').then((module) => ({ default: module.AutoTradeLifecycleList })));
const AutoTradeRecordList = lazy(() => import('./pages/AutoTradeRecordList').then((module) => ({ default: module.AutoTradeRecordList })));
const ClosePositionRecordList = lazy(() => import('./pages/ClosePositionRecordList').then((module) => ({ default: module.ClosePositionRecordList })));
const ContractCandidateList = lazy(() => import('./pages/ContractCandidateList').then((module) => ({ default: module.ContractCandidateList })));
const CurrentOkxOrderList = lazy(() => import('./pages/CurrentOkxOrderList').then((module) => ({ default: module.CurrentOkxOrderList })));
const PendingOrderList = lazy(() => import('./pages/PendingOrderList').then((module) => ({ default: module.PendingOrderList })));
const SecurityPage = lazy(() => import('./pages/SecurityPage').then((module) => ({ default: module.SecurityPage })));
const SystemControlPage = lazy(() => import('./pages/SystemControlPage').then((module) => ({ default: module.SystemControlPage })));
const PositionsPage = lazy(() => import('./pages/PositionsPage').then((module) => ({ default: module.PositionsPage })));

export default function App() {
  return (
    <Suspense fallback={<Box display="grid" minHeight="100vh" sx={{ placeItems: 'center' }}><CircularProgress size={28} /></Box>}>
    <Admin
      title="量化交易终端"
      dataProvider={quantDataProvider}
      authProvider={authProvider}
      dashboard={Dashboard}
      loginPage={LoginPage}
      layout={AppLayout}
      theme={theme}
      requireAuth
    >
      <Resource
        name="contractCandidates"
        list={ContractCandidateList}
        icon={appIcons.contract}
        options={{ label: 'OKX合约' }}
      />
      <Resource
        name="pendingOrders"
        list={PendingOrderList}
        icon={appIcons.pending}
        options={{ label: '待确认订单' }}
      />
      <Resource
        name="autoTradeRecords"
        list={AutoTradeRecordList}
        icon={appIcons.autoTrade}
        options={{ label: '自动交易复盘' }}
      />
      <Resource
        name="autoTradeLifecycle"
        list={AutoTradeLifecycleList}
        icon={appIcons.lifecycle}
        options={{ label: '交易生命周期' }}
      />
      <Resource
        name="currentOkxOrders"
        list={CurrentOkxOrderList}
        icon={appIcons.okxOrders}
        options={{ label: '当前委托/保护单' }}
      />
      <Resource
        name="closePositionRecords"
        list={ClosePositionRecordList}
        icon={appIcons.pending}
        options={{ label: '平仓记录' }}
      />
      <CustomRoutes>
        <Route path="/account-binding" element={<AccountBindingPage />} />
        <Route path="/account-risk" element={<AccountRiskPage />} />
        <Route path="/security" element={<SecurityPage />} />
        <Route path="/system-control" element={<SystemControlPage />} />
        <Route path="/positions" element={<PositionsPage />} />
      </CustomRoutes>
    </Admin>
    </Suspense>
  );
}
