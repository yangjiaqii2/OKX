import { Admin, CustomRoutes, Resource } from 'react-admin';
import { Route } from 'react-router-dom';
import { quantDataProvider } from './api/quantDataProvider';
import { authProvider } from './api/authProvider';
import { AppLayout, appIcons } from './layout';
import { AccountBindingPage } from './pages/AccountBindingPage';
import { AccountRiskPage } from './pages/AccountRiskPage';
import { AutoTradeRecordList } from './pages/AutoTradeRecordList';
import { ContractCandidateList } from './pages/ContractCandidateList';
import { Dashboard } from './pages/Dashboard';
import { LoginPage } from './pages/LoginPage';
import { PendingOrderList } from './pages/PendingOrderList';
import { SystemControlPage } from './pages/SystemControlPage';
import { theme } from './theme';

export default function App() {
  return (
    <Admin
      title="量化实盘控制台"
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
        options={{ label: '自动交易记录' }}
      />
      <CustomRoutes>
        <Route path="/account-binding" element={<AccountBindingPage />} />
        <Route path="/account-risk" element={<AccountRiskPage />} />
        <Route path="/system-control" element={<SystemControlPage />} />
      </CustomRoutes>
    </Admin>
  );
}
