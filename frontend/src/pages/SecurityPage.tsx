import AdminPanelSettingsIcon from '@mui/icons-material/AdminPanelSettings';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import LockResetIcon from '@mui/icons-material/LockReset';
import PersonAddAlt1Icon from '@mui/icons-material/PersonAddAlt1';
import ToggleOffIcon from '@mui/icons-material/ToggleOff';
import ToggleOnIcon from '@mui/icons-material/ToggleOn';
import {
  Alert,
  Box,
  Button,
  Chip,
  FormControl,
  InputLabel,
  MenuItem,
  Paper,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { FormEvent, useEffect, useState } from 'react';
import { useNotify } from 'react-admin';
import { clearAuthSession, getAuthRole, getAuthUser } from '../api/auth';
import { quantFetch } from '../api/http';

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
  const notify = useNotify();
  const username = getAuthUser();
  const role = getAuthRole();
  const isAdmin = role === 'ADMIN';
  const [users, setUsers] = useState<AuthUserView[]>([]);
  const [loadingUsers, setLoadingUsers] = useState(false);
  const [passwordForm, setPasswordForm] = useState({
    oldPassword: '',
    newPassword: '',
    confirmPassword: '',
  });
  const [createForm, setCreateForm] = useState<{
    username: string;
    password: string;
    role: AuthRole;
    enabled: boolean;
  }>({
    username: '',
    password: '',
    role: 'USER',
    enabled: true,
  });

  async function loadUsers() {
    if (!isAdmin) {
      return;
    }
    setLoadingUsers(true);
    try {
      setUsers(await quantFetch<AuthUserView[]>('/auth/users'));
    } catch (error) {
      notify(error instanceof Error ? error.message : '用户列表加载失败', { type: 'error' });
    } finally {
      setLoadingUsers(false);
    }
  }

  useEffect(() => {
    void loadUsers();
  }, [isAdmin]);

  async function submitPassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (passwordForm.newPassword !== passwordForm.confirmPassword) {
      notify('两次输入的新密码不一致', { type: 'warning' });
      return;
    }
    await quantFetch('/auth/password/change', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        oldPassword: passwordForm.oldPassword,
        newPassword: passwordForm.newPassword,
      }),
    });
    try {
      await quantFetch('/auth/logout', { method: 'POST' });
    } catch {
      // Local session must be cleared even if logout is already invalid.
    }
    clearAuthSession();
    notify('密码已修改，请重新登录', { type: 'success' });
    window.location.assign('/login');
  }

  async function submitCreateUser(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await quantFetch<AuthUserView>('/auth/users', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(createForm),
    });
    notify('用户已创建', { type: 'success' });
    setCreateForm({ username: '', password: '', role: 'USER', enabled: true });
    await loadUsers();
  }

  async function toggleEnabled(user: AuthUserView) {
    await quantFetch<AuthUserView>(`/auth/users/${encodeURIComponent(user.username)}/enabled`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled: !user.enabled }),
    });
    notify(user.enabled ? '用户已停用' : '用户已启用', { type: 'success' });
    await loadUsers();
  }

  return (
    <Box sx={{ p: { xs: 2, md: 3 }, maxWidth: 1180 }}>
      <Stack spacing={2.5}>
        <Box>
          <Typography variant="h4" fontWeight={950} sx={{ letterSpacing: 0 }}>
            账号安全
          </Typography>
          <Typography color="text.secondary">
            管理控制台登录用户、密码和账号启停状态。
          </Typography>
        </Box>

        <Paper
          component="form"
          onSubmit={(event) => void submitPassword(event)}
          sx={{ p: 2.5, borderRadius: 2, border: '1px solid rgba(148, 163, 184, 0.14)' }}
        >
          <Stack spacing={2}>
            <Stack direction="row" spacing={1} alignItems="center">
              <LockResetIcon color="primary" />
              <Box>
                <Typography fontWeight={900}>修改当前账号密码</Typography>
                <Typography variant="body2" color="text.secondary">
                  当前登录：{username || '未知用户'} {role ? `(${role})` : ''}
                </Typography>
              </Box>
            </Stack>
            <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5}>
              <TextField
                required
                label="旧密码"
                type="password"
                value={passwordForm.oldPassword}
                autoComplete="current-password"
                onChange={(event) => setPasswordForm((current) => ({ ...current, oldPassword: event.target.value }))}
                sx={{ flex: 1 }}
              />
              <TextField
                required
                label="新密码"
                type="password"
                value={passwordForm.newPassword}
                autoComplete="new-password"
                onChange={(event) => setPasswordForm((current) => ({ ...current, newPassword: event.target.value }))}
                sx={{ flex: 1 }}
              />
              <TextField
                required
                label="确认新密码"
                type="password"
                value={passwordForm.confirmPassword}
                autoComplete="new-password"
                onChange={(event) => setPasswordForm((current) => ({ ...current, confirmPassword: event.target.value }))}
                sx={{ flex: 1 }}
              />
            </Stack>
            <Box>
              <Button type="submit" variant="contained" startIcon={<CheckCircleIcon />}>
                保存新密码
              </Button>
            </Box>
          </Stack>
        </Paper>

        {!isAdmin && (
          <Alert severity="info">
            当前账号不是管理员，只能修改自己的密码。
          </Alert>
        )}

        {isAdmin && (
          <Paper
            component="form"
            onSubmit={(event) => void submitCreateUser(event)}
            sx={{ p: 2.5, borderRadius: 2, border: '1px solid rgba(148, 163, 184, 0.14)' }}
          >
            <Stack spacing={2}>
              <Stack direction="row" spacing={1} alignItems="center">
                <PersonAddAlt1Icon color="primary" />
                <Box>
                  <Typography fontWeight={900}>创建登录用户</Typography>
                  <Typography variant="body2" color="text.secondary">
                    新用户创建后即可用对应账号密码登录控制台。
                  </Typography>
                </Box>
              </Stack>
              <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5}>
                <TextField
                  required
                  label="用户名"
                  value={createForm.username}
                  autoComplete="off"
                  onChange={(event) => setCreateForm((current) => ({ ...current, username: event.target.value }))}
                  sx={{ flex: 1 }}
                />
                <TextField
                  required
                  label="初始密码"
                  type="password"
                  value={createForm.password}
                  autoComplete="new-password"
                  onChange={(event) => setCreateForm((current) => ({ ...current, password: event.target.value }))}
                  sx={{ flex: 1 }}
                />
                <FormControl sx={{ minWidth: 160 }}>
                  <InputLabel id="role-label">角色</InputLabel>
                  <Select
                    labelId="role-label"
                    label="角色"
                    value={createForm.role}
                    onChange={(event) => setCreateForm((current) => ({ ...current, role: event.target.value as AuthRole }))}
                  >
                    <MenuItem value="USER">普通用户</MenuItem>
                    <MenuItem value="ADMIN">管理员</MenuItem>
                  </Select>
                </FormControl>
              </Stack>
              <Box>
                <Button type="submit" variant="contained" startIcon={<PersonAddAlt1Icon />}>
                  创建用户
                </Button>
              </Box>
            </Stack>
          </Paper>
        )}

        {isAdmin && (
          <Paper sx={{ p: 2.5, borderRadius: 2, border: '1px solid rgba(148, 163, 184, 0.14)' }}>
            <Stack spacing={2}>
              <Stack direction="row" spacing={1} alignItems="center">
                <AdminPanelSettingsIcon color="primary" />
                <Box>
                  <Typography fontWeight={900}>用户列表</Typography>
                  <Typography variant="body2" color="text.secondary">
                    启停用户不会影响已登录会话之外的历史审计数据。
                  </Typography>
                </Box>
              </Stack>
              <Box sx={{ width: '100%', overflowX: 'auto' }}>
                <Table size="small" sx={{ minWidth: 620 }}>
                  <TableHead>
                    <TableRow>
                      <TableCell>用户名</TableCell>
                      <TableCell>角色</TableCell>
                      <TableCell>状态</TableCell>
                      <TableCell>最后登录</TableCell>
                      <TableCell align="right">操作</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {users.map((user) => (
                      <TableRow key={user.id ?? user.username}>
                        <TableCell>{user.username}</TableCell>
                        <TableCell>{user.role === 'ADMIN' ? '管理员' : '普通用户'}</TableCell>
                        <TableCell>
                          <Chip
                            size="small"
                            color={user.enabled ? 'success' : 'default'}
                            label={user.enabled ? '启用' : '停用'}
                          />
                        </TableCell>
                        <TableCell>{formatTime(user.lastLoginAt)}</TableCell>
                        <TableCell align="right">
                          <Button
                            size="small"
                            variant="outlined"
                            color={user.enabled ? 'warning' : 'success'}
                            startIcon={user.enabled ? <ToggleOffIcon /> : <ToggleOnIcon />}
                            disabled={loadingUsers || user.username === username}
                            onClick={() => void toggleEnabled(user)}
                          >
                            {user.enabled ? '停用' : '启用'}
                          </Button>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </Box>
            </Stack>
          </Paper>
        )}
      </Stack>
    </Box>
  );
}

function formatTime(value?: string) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '-';
  }
  return date.toLocaleString();
}
