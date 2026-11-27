import {
  Activity,
  Bookmark,
  CandlestickChart,
  KeyRound,
  LayoutDashboard,
  ListChecks,
  LockKeyhole,
  ScrollText,
  Settings2,
  Shield,
  Timer,
  UserRound,
  Wallet,
  Waypoints,
} from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { useGetIdentity, useLogout } from 'react-admin';
import { NavLink, useLocation, useNavigate } from 'react-router-dom';
import { getAuthRole } from './api/auth';
import { LanguageSwitch } from './components/LanguageSwitch';
import { RuntimeStatusBar } from './components/RuntimeStatusBar';
import { ThemeToggle } from './components/ThemeToggle';
import { t, useLanguage } from './i18n';
import { cn } from './lib/cn';

const trade = [
  { to: '/', label: '总览', icon: LayoutDashboard, end: true },
  { to: '/contractCandidates', label: 'OKX合约', icon: CandlestickChart },
  { to: '/positions', label: '当前持仓', icon: Wallet },
  { to: '/pendingOrders', label: '待确认订单', icon: ListChecks },
  { to: '/currentOkxOrders', label: '当前委托/保护单', icon: Timer },
];

const review = [
  { to: '/autoTradeRecords', label: '交易复盘', icon: Activity },
  { to: '/autoTradeLifecycle', label: '生命周期', icon: Waypoints },
  { to: '/closePositionRecords', label: '平仓记录', icon: Bookmark },
];

const system = [
  { to: '/account-risk', label: '账户与风控', icon: Shield },
  { to: '/account-binding', label: '账号绑定', icon: KeyRound },
  { to: '/security', label: '账号安全', icon: UserRound },
  { to: '/system-control', label: '系统控制', icon: Settings2 },
];

function Brand() {
  return (
    <div className="mb-5 flex h-8 items-center gap-2.5 px-2">
      <i className="size-2.5 shrink-0 rounded-[3px] bg-accent" />
      <b className="font-sans text-[14px] font-semibold tracking-[-0.022em]">Quant Desk</b>
    </div>
  );
}

function NavItem({ to, label, icon: Icon, end = false, onClick }: { to: string; label: string; icon: typeof LayoutDashboard; end?: boolean; onClick?: () => void }) {
  return (
    <NavLink
      to={to}
      end={end}
      onClick={onClick}
      className={({ isActive }) => cn(
        'flex h-8 items-center gap-2 whitespace-nowrap rounded-lg px-2 text-[13px] leading-[18px]',
        isActive ? 'bg-accent-soft font-semibold text-accent-ink' : 'text-[#2A303A] hover:bg-sunken dark:text-ink',
      )}
    >
      {({ isActive }) => (
        <>
          <Icon size={16} strokeWidth={1.6} className={isActive ? 'text-accent-ink' : 'text-[#66717E] dark:text-muted'} />
          {t(label)}
        </>
      )}
    </NavLink>
  );
}

function NavGroups({ onNavigate }: { onNavigate?: () => void }) {
  useLanguage();
  return (
    <nav className="flex flex-col" aria-label={t('主导航')}>
      <p className="mb-1.5 mt-0 px-2 text-[12px] font-medium leading-4 text-muted">{t('交易')}</p>
      {trade.map((item) => <NavItem key={item.to} {...item} onClick={onNavigate} />)}
      <p className="mb-1.5 mt-[18px] px-2 text-[12px] font-medium leading-4 text-muted">{t('复盘')}</p>
      {review.map((item) => <NavItem key={item.to} {...item} onClick={onNavigate} />)}
      <p className="mb-1.5 mt-[18px] px-2 text-[12px] font-medium leading-4 text-muted">{t('系统')}</p>
      {system.filter((item) => item.to !== '/system-control' || getAuthRole() === 'ADMIN').map((item) => <NavItem key={item.to} {...item} onClick={onNavigate} />)}
    </nav>
  );
}

function UserMenu() {
  useLanguage();
  const { data } = useGetIdentity();
  const logout = useLogout();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const name = data?.fullName || 'admin';
  return (
    <div className="relative">
      <button type="button" className="flex items-center gap-2 text-[13px] font-medium" onClick={() => setOpen((value) => !value)} aria-expanded={open}>
        <span className="grid size-6 place-items-center rounded-full bg-[#E5E8EF] text-[11px] font-semibold text-ink dark:bg-sunken dark:text-ink">{String(name).slice(0, 1).toUpperCase()}</span>
        <span className="hidden sm:inline">{name}</span>
      </button>
      {open ? (
        <>
          <button type="button" className="fixed inset-0 z-30 cursor-default" aria-label={t('关闭')} onClick={() => setOpen(false)} />
          <div className="absolute right-0 top-9 z-40 w-40 rounded-xl bg-raised p-1 shadow-[0_1px_1px_rgba(16,20,28,0.05),0_16px_40px_rgba(16,20,28,0.12)]">
            <button type="button" className="flex h-9 w-full items-center gap-2 rounded-lg px-2 text-left text-[13px] hover:bg-sunken" onClick={() => { setOpen(false); navigate('/security'); }}>
              <LockKeyhole size={14} /> {t('账号安全')}
            </button>
            <button type="button" className="flex h-9 w-full items-center rounded-lg px-2 text-left text-[13px] hover:bg-sunken" onClick={() => void logout()}>
              {t('退出登录')}
            </button>
          </div>
        </>
      ) : null}
    </div>
  );
}

function Sidebar({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <aside className="min-h-full px-3 pb-4 pt-5">
      <Brand />
      <NavGroups onNavigate={onNavigate} />
    </aside>
  );
}

function MobileNav() {
  useLanguage();
  const location = useLocation();
  const navigate = useNavigate();
  const [more, setMore] = useState(false);
  const main = [
    { label: '总览', path: '/', icon: LayoutDashboard },
    { label: '机会', path: '/contractCandidates', icon: CandlestickChart },
    { label: '订单', path: '/pendingOrders', icon: ScrollText },
    { label: '持仓', path: '/positions', icon: Wallet },
  ];
  const moreItems = [
    ['当前委托/保护单', '/currentOkxOrders'], ['交易复盘', '/autoTradeRecords'], ['生命周期', '/autoTradeLifecycle'],
    ['平仓记录', '/closePositionRecords'], ['账户与风控', '/account-risk'], ['账号绑定', '/account-binding'],
    ['账号安全', '/security'], ['系统控制', '/system-control'],
  ] as const;
  return (
    <>
      <nav className="fixed inset-x-0 bottom-0 z-40 border-t border-line bg-canvas pb-[env(safe-area-inset-bottom)] md:hidden" aria-label={t('主导航')}>
        <div className="grid h-16 grid-cols-5">
          {main.map((item) => {
            const active = location.pathname === item.path;
            const Icon = item.icon;
            return (
              <button key={item.path} type="button" className={cn('flex flex-col items-center justify-center gap-1 text-[11px]', active ? 'text-accent-ink' : 'text-muted')} onClick={() => navigate(item.path)}>
                <Icon size={18} strokeWidth={1.6} />
                {t(item.label)}
              </button>
            );
          })}
          <button type="button" className="flex flex-col items-center justify-center gap-1 text-[11px] text-muted" onClick={() => setMore(true)}>
            <ListChecks size={18} />
            {t('更多')}
          </button>
        </div>
      </nav>
      {more ? (
        <div className="fixed inset-0 z-50 md:hidden">
          <button type="button" className="absolute inset-0 bg-[var(--overlay)]" aria-label={t('关闭')} onClick={() => setMore(false)} />
          <div className="absolute inset-x-0 bottom-0 max-h-[80dvh] overflow-auto rounded-t-2xl bg-raised px-2 pb-[calc(16px+env(safe-area-inset-bottom))] pt-3">
            <p className="px-3 pb-2 text-sm font-semibold">{t('更多功能')}</p>
            {moreItems.filter(([, path]) => path !== '/system-control' || getAuthRole() === 'ADMIN').map(([label, path]) => (
              <button key={path} type="button" className={cn('flex h-11 w-full items-center rounded-lg px-3 text-left text-[14px]', location.pathname === path && 'bg-accent-soft text-accent-ink')} onClick={() => { setMore(false); navigate(path); }}>
                {t(label)}
              </button>
            ))}
          </div>
        </div>
      ) : null}
    </>
  );
}

export const appIcons = {
  contract: CandlestickChart,
  pending: Timer,
  autoTrade: ScrollText,
  lifecycle: Waypoints,
  okxOrders: ListChecks,
};

export function AppLayout({ children }: { children?: ReactNode }) {
  const [drawer, setDrawer] = useState(false);
  return (
    <div className="min-h-dvh bg-canvas text-ink">
      <div className="md:grid md:min-h-dvh md:grid-cols-[228px_minmax(0,1fr)]">
        <div className="sticky top-0 hidden h-dvh overflow-y-auto border-r border-line bg-canvas md:block">
          <Sidebar />
        </div>
        <div className="min-w-0 pb-20 md:pb-0">
          <header className="sticky top-0 z-20 flex h-[52px] items-center justify-end gap-3 border-b border-line bg-canvas/90 px-4 backdrop-blur sm:gap-4 sm:px-8 md:px-9">
            <button type="button" className="mr-auto text-[13px] font-semibold md:hidden" onClick={() => setDrawer(true)}>Quant Desk</button>
            <RuntimeStatusBar />
            <LanguageSwitch />
            <ThemeToggle />
            <UserMenu />
          </header>
          {children}
        </div>
      </div>
      {drawer ? (
        <div className="fixed inset-0 z-50 md:hidden">
          <button type="button" className="absolute inset-0 bg-[var(--overlay)]" aria-label={t('关闭')} onClick={() => setDrawer(false)} />
          <div className="absolute inset-y-0 left-0 w-[260px] overflow-auto border-r border-line bg-canvas">
            <Sidebar onNavigate={() => setDrawer(false)} />
          </div>
        </div>
      ) : null}
      <MobileNav />
    </div>
  );
}
