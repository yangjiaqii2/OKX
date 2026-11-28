import { lazy, Suspense, useEffect, useState } from 'react';
import { Admin, CustomRoutes, Resource } from 'react-admin';
import { Route } from 'react-router-dom';
import { getAuthToken, subscribeAuthChange } from './api/auth';
import { authProvider } from './api/authProvider';
import { quantDataProvider } from './api/quantDataProvider';
import { AppNotification } from './components/AppNotification';
import { t, useAdminI18n } from './i18n';
import { appIcons, AppLayout } from './layout';
import { Dashboard } from './pages/Dashboard';
import { LandingPage } from './pages/LandingPage';
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
const AgentsPage = lazy(() => import('./pages/AgentsPage').then((module) => ({ default: module.AgentsPage })));
const LlmSettingsPage = lazy(() => import('./pages/LlmSettingsPage').then((module) => ({ default: module.LlmSettingsPage })));
const SecurityPage = lazy(() => import('./pages/SecurityPage').then((module) => ({ default: module.SecurityPage })));
const SystemControlPage = lazy(() => import('./pages/SystemControlPage').then((module) => ({ default: module.SystemControlPage })));
const PositionsPage = lazy(() => import('./pages/PositionsPage').then((module) => ({ default: module.PositionsPage })));

function isLandingHash(hash: string) {
  return hash === '' || hash === '#' || hash === '#/';
}

export default function App() {
  const i18nProvider = useAdminI18n();
  const [hasToken, setHasToken] = useState(() => Boolean(getAuthToken()));
  const [hash, setHash] = useState(() => window.location.hash);
  const [preferredAuthMode, setPreferredAuthMode] = useState<'login' | 'register'>(() =>
    window.location.hash.includes('mode=register') ? 'register' : 'login',
  );

  useEffect(() => {
    const unsubscribe = subscribeAuthChange(() => {
      setHasToken(Boolean(getAuthToken()));
    });
    const onHashChange = () => {
      const nextHash = window.location.hash;
      setHash(nextHash);
      if (nextHash.includes('mode=register')) {
        setPreferredAuthMode('register');
      } else if (nextHash.startsWith('#/login')) {
        setPreferredAuthMode((prev) => (nextHash.includes('mode=login') ? 'login' : prev));
      }
    };
    window.addEventListener('hashchange', onHashChange);
    return () => {
      unsubscribe();
      window.removeEventListener('hashchange', onHashChange);
    };
  }, []);

  if (!hasToken && isLandingHash(hash)) {
    return (
      <LandingPage
        onOpenAuth={(mode) => {
          setPreferredAuthMode(mode);
          const nextHash = mode === 'register' ? '#/login?mode=register' : '#/login?mode=login';
          window.location.hash = nextHash;
          setHash(nextHash);
        }}
      />
    );
  }

  return (
    <Suspense fallback={<div className="grid min-h-dvh place-items-center bg-canvas"><div className="size-5 animate-spin rounded-full border-2 border-line-strong border-t-accent" /></div>}>
      <Admin
        title={t('量化交易终端')}
        dataProvider={quantDataProvider}
        authProvider={authProvider}
        i18nProvider={i18nProvider}
        dashboard={Dashboard}
        loginPage={() => (
          <LoginPage
            initialMode={preferredAuthMode}
            onBackHome={() => {
              window.location.hash = '#/';
              setHash('#/');
            }}
          />
        )}
        layout={AppLayout}
        notification={AppNotification}
        theme={theme}
        requireAuth
      >
        <Resource name="contractCandidates" list={ContractCandidateList} icon={appIcons.contract} options={{ label: t('OKX合约') }} />
        <Resource name="pendingOrders" list={PendingOrderList} icon={appIcons.pending} options={{ label: t('待确认订单') }} />
        <Resource name="autoTradeRecords" list={AutoTradeRecordList} icon={appIcons.autoTrade} options={{ label: t('自动交易复盘') }} />
        <Resource name="autoTradeLifecycle" list={AutoTradeLifecycleList} icon={appIcons.lifecycle} options={{ label: t('交易生命周期') }} />
        <Resource name="currentOkxOrders" list={CurrentOkxOrderList} icon={appIcons.okxOrders} options={{ label: t('当前委托/保护单') }} />
        <Resource name="closePositionRecords" list={ClosePositionRecordList} icon={appIcons.pending} options={{ label: t('平仓记录') }} />
        <CustomRoutes>
          <Route path="/agents" element={<AgentsPage />} />
          <Route path="/account-binding" element={<AccountBindingPage />} />
          <Route path="/llm-settings" element={<LlmSettingsPage />} />
          <Route path="/account-risk" element={<AccountRiskPage />} />
          <Route path="/security" element={<SecurityPage />} />
          <Route path="/system-control" element={<SystemControlPage />} />
          <Route path="/positions" element={<PositionsPage />} />
        </CustomRoutes>
      </Admin>
    </Suspense>
  );
}
