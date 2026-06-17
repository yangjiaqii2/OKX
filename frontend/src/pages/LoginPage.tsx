import LockIcon from '@mui/icons-material/Lock';
import ShieldIcon from '@mui/icons-material/Shield';
import {
  Alert,
  Box,
  Button,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { FormEvent, useState } from 'react';
import { useLogin, useNotify } from 'react-admin';
import { glassCard } from '../components/glass';

export function LoginPage() {
  const login = useLogin();
  const notify = useNotify();
  const [submitting, setSubmitting] = useState(false);
  const [form, setForm] = useState({ username: 'admin', password: '' });

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    try {
      await login(form);
    } catch (error) {
      notify(error instanceof Error ? error.message : '登录失败', { type: 'error' });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Box
      sx={{
        minHeight: '100dvh',
        bgcolor: 'background.default',
        display: 'grid',
        placeItems: 'center',
        px: 2,
        position: 'relative',
        overflow: 'hidden',
      }}
    >
      <Paper
        component="form"
        onSubmit={(event) => void submit(event)}
        sx={{
          ...glassCard,
          width: '100%',
          maxWidth: 420,
          p: 3,
          border: '1px solid rgba(148, 163, 184, 0.18)',
          zIndex: 1,
        }}
      >
        <Stack spacing={2.25}>
          <Stack direction="row" spacing={1.25} alignItems="center">
            <Box
              sx={{
                width: 36,
                height: 36,
                borderRadius: 1.25,
                display: 'grid',
                placeItems: 'center',
                bgcolor: 'rgba(20, 184, 166, 0.14)',
                color: 'primary.main',
                border: '1px solid rgba(20, 184, 166, 0.28)',
              }}
            >
              <LockIcon fontSize="small" />
            </Box>
            <Box>
              <Typography variant="h5" fontWeight={800}>
                量化实盘控制台
              </Typography>
              <Typography variant="body2" color="text.secondary">
                登录后访问 OKX 合约交易功能
              </Typography>
            </Box>
          </Stack>

          <Stack
            direction="row"
            spacing={1}
            alignItems="center"
            sx={{
              px: 1.25,
              py: 1,
              borderRadius: 1,
              bgcolor: 'rgba(20, 184, 166, 0.08)',
              border: '1px solid rgba(20, 184, 166, 0.16)',
            }}
          >
            <ShieldIcon fontSize="small" color="success" />
            <Typography variant="caption" color="text.secondary" fontWeight={700}>
              风控优先，人工确认后才提交 OKX 实盘。
            </Typography>
          </Stack>

          <Alert severity="warning">
            默认账号来自后端配置。生产环境请设置 QUANT_AUTH_USERNAME 和 QUANT_AUTH_PASSWORD。
          </Alert>

          <TextField
            required
            label="用户名"
            value={form.username}
            autoComplete="username"
            onChange={(event) => setForm((current) => ({ ...current, username: event.target.value }))}
          />
          <TextField
            required
            label="密码"
            type="password"
            value={form.password}
            autoComplete="current-password"
            onChange={(event) => setForm((current) => ({ ...current, password: event.target.value }))}
          />
          <Button type="submit" variant="contained" size="large" disabled={submitting}>
            登录
          </Button>
        </Stack>
      </Paper>
    </Box>
  );
}
