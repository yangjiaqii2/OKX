import { FormEvent, useEffect, useState } from 'react';
import { useLogout, useNotify } from 'react-admin';
import { getAuthRole, getAuthUser } from '../api/auth';
import { quantFetch } from '../api/http';
import { PageHeader, PageShell } from '../components/PageShell';
import { PanelState } from '../components/PanelState';
import { ScrollableTable } from '../components/ScrollableTable';
import { Alert } from '../components/ui/alert';
import { Button } from '../components/ui/button';
import { Dialog, DialogContent } from '../components/ui/dialog';
import { ConfirmDialog } from '../components/ui/confirm-dialog';
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
  const logout = useLogout();
  const username = getAuthUser();
  const role = getAuthRole();
  const isAdmin = role === 'ADMIN';
  const [users, setUsers] = useState<AuthUserView[]>([]);
  const [loadingUsers, setLoadingUsers] = useState(false);
  const [passwordForm, setPasswordForm] = useState({ oldPassword: '', newPassword: '', confirmPassword: '' });
  const [createForm, setCreateForm] = useState<{ username: string; password: string; role: AuthRole; enabled: boolean }>({ username: '', password: '', role: 'USER', enabled: true });
  const [showCreatePassword, setShowCreatePassword] = useState(false);
  const [changingPassword, setChangingPassword] = useState(false);
  const [creatingUser, setCreatingUser] = useState(false);
  const [togglingUser, setTogglingUser] = useState('');
  const [disableTarget, setDisableTarget] = useState<AuthUserView | null>(null);
  const [resetTarget, setResetTarget] = useState<AuthUserView | null>(null);
  const [resetPassword, setResetPassword] = useState('');
  const [resetting, setResetting] = useState(false);

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
    if (changingPassword) return;
    if (passwordForm.newPassword !== passwordForm.confirmPassword) {
      notify(t('两次输入的新密码不一致'), { type: 'warning' });
      return;
    }
    setChangingPassword(true);
    try {
      await quantFetch('/auth/password/change', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ oldPassword: passwordForm.oldPassword, newPassword: passwordForm.newPassword }),
      });
      notify(t('密码已修改，请重新登录'), { type: 'success' });
      await logout({}, '/login', false);
    } catch (error) {
      notify(error instanceof Error ? t(error.message) : t('修改密码失败'), { type: 'error' });
    } finally {
      setChangingPassword(false);
    }
  }

  async function submitCreateUser(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (creatingUser || !isAdmin) return;
    if (createForm.password.length < 6) {
      notify(t('密码长度不能少于 6 位'), { type: 'warning' });
      return;
    }
    setCreatingUser(true);
    try {
      await quantFetch<AuthUserView>('/auth/users', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(createForm),
      });
      notify(t('用户已创建'), { type: 'success' });
      setCreateForm({ username: '', password: '', role: 'USER', enabled: true });
      await loadUsers();
    } catch (error) {
      notify(error instanceof Error ? t(error.message) : t('创建用户失败'), { type: 'error' });
    } finally {
      setCreatingUser(false);
    }
  }

  async function toggleEnabled(user: AuthUserView) {
    if (togglingUser || !isAdmin) return;
    setTogglingUser(user.username);
    try {
      await quantFetch<AuthUserView>(`/auth/users/${encodeURIComponent(user.username)}/enabled`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled: !user.enabled }),
      });
      notify(user.enabled ? t('用户已停用') : t('用户已启用'), { type: 'success' });
      await loadUsers();
    } catch (error) {
      notify(error instanceof Error ? t(error.message) : t('更新用户状态失败'), { type: 'error' });
    } finally {
      setTogglingUser('');
      setDisableTarget(null);
    }
  }

  function closeReset() {
    setResetTarget(null);
    setResetPassword('');
  }

  async function submitReset(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!isAdmin || !resetTarget || resetting || resetTarget.role === 'ADMIN') return;
    if (resetPassword.length < 6) {
      notify(t('密码长度不能少于 6 位'), { type: 'warning' });
      return;
    }
    setResetting(true);
    try {
      await quantFetch<AuthUserView>(`/auth/users/${encodeURIComponent(resetTarget.username)}/password`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ newPassword: resetPassword }),
      });
      notify(t('用户密码已重置'), { type: 'success' });
      closeReset();
    } catch (error) {
      notify(error instanceof Error ? t(error.message) : t('重置密码失败'), { type: 'error' });
    } finally {
      setResetting(false);
    }
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
        <Button className="mt-4" variant="primary" type="submit" disabled={changingPassword} aria-busy={changingPassword}>{changingPassword ? t('提交中') : t('保存新密码')}</Button>
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
          <Button className="mt-4" variant="primary" type="submit" disabled={creatingUser} aria-busy={creatingUser}>{creatingUser ? t('提交中') : t('创建用户')}</Button>
        </form>
      ) : null}
      {isAdmin ? (
        <section className="mt-10">
          <h2 className="text-[15px] font-semibold">{t('用户列表')}</h2>
          <p className="mt-1 text-[13px] text-muted">{t('停用后无法登录，已有会话在停用期间下次向后端验证时失效。')}</p>
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
                        <div className="flex justify-end gap-2">
                          {user.role === 'USER' && <Button variant="ghost"
                            disabled={loadingUsers || resetting || Boolean(togglingUser)}
                            onClick={() => setResetTarget(user)}>{t('重置密码')}</Button>}
                          <Button variant={user.enabled ? 'warning' : 'ghost'}
                            disabled={loadingUsers || resetting || Boolean(togglingUser) || user.username === username}
                            aria-busy={togglingUser === user.username}
                            onClick={() => user.enabled ? setDisableTarget(user) : void toggleEnabled(user)}>
                            {togglingUser === user.username ? t('提交中') : user.enabled ? t('停用') : t('启用')}
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </ScrollableTable>
            )}
          </div>
        </section>
      ) : null}
      {resetTarget && (
        <Dialog open onOpenChange={(open) => { if (!open && !resetting) closeReset(); }}>
          <DialogContent
            title={t('重置用户 {username} 的密码', { username: resetTarget.username })}
            description={t('确认后，用户下次登录需要使用新密码。已有会话仍可用。')}
            hideClose
          >
            <form onSubmit={(event) => void submitReset(event)}>
              <Field label={t('新密码')}>
                <Input required type="password" autoComplete="new-password" value={resetPassword}
                  disabled={resetting} onChange={(event) => setResetPassword(event.target.value)} />
              </Field>
              <div className="mt-5 flex justify-end gap-2">
                <Button onClick={closeReset} disabled={resetting}>{t('返回')}</Button>
                <Button type="submit" variant="warning" disabled={resetting} aria-busy={resetting}>
                  {resetting ? t('提交中') : t('确认重置')}
                </Button>
              </div>
            </form>
          </DialogContent>
        </Dialog>
      )}
      {disableTarget && (
        <ConfirmDialog
          open
          pending={Boolean(togglingUser)}
          title={t('停用用户 {username}', { username: disableTarget.username })}
          content={t('停用后无法登录，已有会话在停用期间下次向后端验证时失效。')}
          confirmLabel={togglingUser ? t('提交中') : t('停用')}
          cancelLabel={t('返回')}
          tone="warning"
          onConfirm={() => void toggleEnabled(disableTarget)}
          onClose={() => setDisableTarget(null)}
        />
      )}
    </PageShell>
  );
}

function formatTime(value?: string) {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString(getLanguage());
}
