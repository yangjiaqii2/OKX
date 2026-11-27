import { FormEvent, useEffect, useState } from 'react';
import { useNotify } from 'react-admin';
import { clearAuthSession, getAuthRole, getAuthUser } from '../api/auth';
import { quantFetch } from '../api/http';
import { PageHeader, PageShell } from '../components/PageShell';
import { PanelState } from '../components/PanelState';
import { ScrollableTable } from '../components/ScrollableTable';
import { Alert } from '../components/ui/alert';
import { Button } from '../components/ui/button';
import { Field, Input, SelectInput } from '../components/ui/field';
import { TableBody, TableCell, TableHead, TableRow } from '../components/ui/table';
import { getLanguage, t, useLanguage } from '../i18n';

type AuthRole = 'ADMIN' | 'USER';
type AuthUserView = {
  id: number;
  username: string;
  role: AuthRole;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
  lastLoginAt?: string;
};

export function SecurityPage() {
  useLanguage();
  const notify = useNotify();
  const username = getAuthUser();
  const role = getAuthRole();
  const isAdmin = role === 'ADMIN';
  const [users, setUsers] = useState<AuthUserView[]>([]);
  const [loadingUsers, setLoadingUsers] = useState(false);
  const [passwordForm, setPasswordForm] = useState({ oldPassword: '', newPassword: '', confirmPassword: '' });
  const [createForm, setCreateForm] = useState<{ username: string; password: string; role: AuthRole; enabled: boolean }>({ username: '', password: '', role: 'USER', enabled: true });
  const [showCreatePassword, setShowCreatePassword] = useState(false);

  async function loadUsers() {
    if (!isAdmin) return;
    setLoadingUsers(true);
    try {
      setUsers(await quantFetch<AuthUserView[]>('/auth/users'));
    } catch (error) {
      notify(error instanceof Error ? error.message : t('用户列表加载失败'), { type: 'error' });
    } finally {
      setLoadingUsers(false);
    }
  }

  useEffect(() => { void loadUsers(); }, [isAdmin]);

  async function submitPassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (passwordForm.newPassword !== passwordForm.confirmPassword) {
      notify(t('两次输入的新密码不一致'), { type: 'warning' });
      return;
    }
    await quantFetch('/auth/password/change', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ oldPassword: passwordForm.oldPassword, newPassword: passwordForm.newPassword }),
    });
    try {
      await quantFetch('/auth/logout', { method: 'POST' });
    } catch {
      // Local session must be cleared even if logout is already invalid.
    }
    clearAuthSession();
    notify(t('密码已修改，请重新登录'), { type: 'success' });
    window.location.assign('/login');
  }

  async function submitCreateUser(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await quantFetch<AuthUserView>('/auth/users', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(createForm),
    });
    notify(t('用户已创建'), { type: 'success' });
    setCreateForm({ username: '', password: '', role: 'USER', enabled: true });
    await loadUsers();
  }

  async function toggleEnabled(user: AuthUserView) {
    await quantFetch<AuthUserView>(`/auth/users/${encodeURIComponent(user.username)}/enabled`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled: !user.enabled }),
    });
    notify(user.enabled ? t('用户已停用') : t('用户已启用'), { type: 'success' });
    await loadUsers();
  }

  return (
    <PageShell title={t('账号安全')} maxWidth={1180}>
      <PageHeader title={t('账号安全')} subtitle={t('管理控制台登录用户、密码和账号启停状态。')} />
      <form className="mt-6 max-w-4xl" onSubmit={(event) => void submitPassword(event)}>
        <h2 className="text-[15px] font-semibold">{t('修改当前账号密码')}</h2>
        <p className="mt-1 text-[13px] text-muted">{t('当前登录：')}{username || t('未知用户')} {role ? `(${role})` : ''}</p>
        <div className="mt-4 grid gap-3 sm:grid-cols-3">
          <Field label={t('旧密码')}><Input required type="password" autoComplete="current-password" value={passwordForm.oldPassword} onChange={(event) => setPasswordForm((current) => ({ ...current, oldPassword: event.target.value }))} /></Field>
          <Field label={t('新密码')}><Input required type="password" autoComplete="new-password" value={passwordForm.newPassword} onChange={(event) => setPasswordForm((current) => ({ ...current, newPassword: event.target.value }))} /></Field>
          <Field label={t('确认新密码')}><Input required type="password" autoComplete="new-password" value={passwordForm.confirmPassword} onChange={(event) => setPasswordForm((current) => ({ ...current, confirmPassword: event.target.value }))} /></Field>
        </div>
        <Button className="mt-4" variant="primary" type="submit">{t('保存新密码')}</Button>
      </form>
      {!isAdmin ? <Alert className="mt-6" severity="info">{t('当前账号不是管理员，只能修改自己的密码。')}</Alert> : null}
      {isAdmin ? (
        <form className="mt-10" onSubmit={(event) => void submitCreateUser(event)}>
          <h2 className="text-[15px] font-semibold">{t('创建登录用户')}</h2>
          <p className="mt-1 text-[13px] text-muted">{t('新用户创建后即可用对应账号密码登录控制台。')}</p>
          <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-[1fr_1fr_160px]">
            <Field label={t('用户名')}><Input required autoComplete="off" value={createForm.username} onChange={(event) => setCreateForm((current) => ({ ...current, username: event.target.value }))} /></Field>
            <Field label={t('初始密码')}>
              <div className="relative">
                <Input required className="pr-16" type={showCreatePassword ? 'text' : 'password'} autoComplete="new-password" value={createForm.password} onChange={(event) => setCreateForm((current) => ({ ...current, password: event.target.value }))} />
                <button type="button" className="absolute right-3 top-1/2 -translate-y-1/2 text-[13px] text-muted" aria-label={showCreatePassword ? t('隐藏初始密码') : t('显示初始密码')} onClick={() => setShowCreatePassword((value) => !value)}>{showCreatePassword ? t('隐藏密码') : t('显示密码')}</button>
              </div>
            </Field>
            <Field label={t('角色')}>
              <SelectInput className="h-12 text-[15px]" value={createForm.role} onChange={(event) => setCreateForm((current) => ({ ...current, role: event.target.value as AuthRole }))}>
                <option value="USER">{t('普通用户')}</option>
                <option value="ADMIN">{t('管理员')}</option>
              </SelectInput>
            </Field>
          </div>
          <Button className="mt-4" variant="primary" type="submit">{t('创建用户')}</Button>
        </form>
      ) : null}
      {isAdmin ? (
        <section className="mt-10">
          <h2 className="text-[15px] font-semibold">{t('用户列表')}</h2>
          <p className="mt-1 text-[13px] text-muted">{t('启停用户不会影响已登录会话之外的历史审计数据。')}</p>
          <div className="mt-4">
            {loadingUsers && users.length === 0 ? <PanelState loading /> : users.length === 0 ? <PanelState>{t('暂无用户。')}</PanelState> : (
              <ScrollableTable label={t('用户列表')}>
                <TableHead>
                  <TableRow>
                    <TableCell>{t('用户名')}</TableCell>
                    <TableCell>{t('角色')}</TableCell>
                    <TableCell>{t('状态')}</TableCell>
                    <TableCell>{t('最后登录')}</TableCell>
                    <TableCell align="right">{t('操作')}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {users.map((user) => (
                    <TableRow key={user.id ?? user.username}>
                      <TableCell>{user.username}</TableCell>
                      <TableCell>{user.role === 'ADMIN' ? t('管理员') : t('普通用户')}</TableCell>
                      <TableCell><span className={user.enabled ? 'text-up' : 'text-muted'}>{user.enabled ? t('启用') : t('停用')}</span></TableCell>
                      <TableCell>{formatTime(user.lastLoginAt)}</TableCell>
                      <TableCell align="right">
                        <Button variant={user.enabled ? 'warning' : 'ghost'} disabled={loadingUsers || user.username === username} onClick={() => void toggleEnabled(user)}>
                          {user.enabled ? t('停用') : t('启用')}
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </ScrollableTable>
            )}
          </div>
        </section>
      ) : null}
    </PageShell>
  );
}

function formatTime(value?: string) {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString(getLanguage());
}
