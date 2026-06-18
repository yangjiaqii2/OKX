import LockIcon from '@mui/icons-material/Lock';
import LoginIcon from '@mui/icons-material/Login';
import ShieldIcon from '@mui/icons-material/Shield';
import VisibilityIcon from '@mui/icons-material/Visibility';
import VisibilityOffIcon from '@mui/icons-material/VisibilityOff';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  IconButton,
  InputAdornment,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { FormEvent, useState } from 'react';
import { useLogin, useNotify } from 'react-admin';

export function LoginPage() {
  const login = useLogin();
  const notify = useNotify();
  const [submitting, setSubmitting] = useState(false);
  const [form, setForm] = useState({ username: 'admin', password: '' });
  const [showPassword, setShowPassword] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const username = form.username.trim();
    if (!username) {
      setErrorMessage('请输入账号');
      return;
    }
    if (!form.password) {
      setErrorMessage('请输入密码');
      return;
    }
    setSubmitting(true);
    setErrorMessage('');
    try {
      await login({ ...form, username });
    } catch (error) {
      const message = error instanceof Error ? error.message : '账号或密码错误';
      setErrorMessage(message || '账号或密码错误');
      setForm((current) => ({ ...current, password: '' }));
      notify(message || '登录失败', { type: 'error' });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Box
      sx={{
        minHeight: '100dvh',
        bgcolor: '#0f141b',
        display: 'grid',
        placeItems: 'center',
        px: 2,
        py: 4,
        position: 'relative',
        overflow: 'hidden',
      }}
    >
      <Paper
        component="form"
        onSubmit={(event) => void submit(event)}
        sx={{
          width: '100%',
          maxWidth: 860,
          display: 'grid',
          gridTemplateColumns: { xs: '1fr', md: '0.9fr 1.1fr' },
          overflow: 'hidden',
          borderRadius: 2,
          border: '1px solid rgba(148, 163, 184, 0.18)',
          bgcolor: 'rgba(17, 24, 39, 0.96)',
          boxShadow: '0 24px 80px rgba(0, 0, 0, 0.36)',
          zIndex: 1,
        }}
      >
        <Stack
          spacing={2.25}
          sx={{
            p: { xs: 3, md: 4 },
            borderRight: { md: '1px solid rgba(148, 163, 184, 0.14)' },
            bgcolor: 'rgba(15, 23, 42, 0.72)',
          }}
        >
          <Stack direction="row" spacing={1.25} alignItems="center">
            <Box
              sx={{
                width: 40,
                height: 40,
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
              <Typography variant="h5" fontWeight={900} sx={{ letterSpacing: 0 }}>
                Quant Desk
              </Typography>
              <Typography variant="body2" color="text.secondary">
                OKX 合约实盘控制台
              </Typography>
            </Box>
          </Stack>

          <Box>
            <Typography variant="h4" fontWeight={950} sx={{ mb: 1, letterSpacing: 0 }}>
              登录后管理交易系统
            </Typography>
            <Typography color="text.secondary" sx={{ lineHeight: 1.7 }}>
              账号已改为数据库用户体系，密码以 PBKDF2 哈希保存。首次进入后可在“账号安全”里修改管理员密码。
            </Typography>
          </Box>

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
              默认管理员：admin / admin123
            </Typography>
          </Stack>
        </Stack>

        <Stack spacing={2.25} sx={{ p: { xs: 3, md: 4 } }}>
          <Box>
            <Typography variant="h5" fontWeight={900} sx={{ letterSpacing: 0 }}>
              用户登录
            </Typography>
            <Typography variant="body2" color="text.secondary">
              使用已创建的系统账号进入控制台。
            </Typography>
          </Box>

          <Alert severity="info" sx={{ alignItems: 'center' }}>
            初始管理员仅用于首次进入系统，登录后请尽快修改密码。
          </Alert>
          {errorMessage ? <Alert severity="error">{errorMessage}</Alert> : null}

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
            type={showPassword ? 'text' : 'password'}
            value={form.password}
            autoComplete="current-password"
            onChange={(event) => setForm((current) => ({ ...current, password: event.target.value }))}
            InputProps={{
              endAdornment: (
                <InputAdornment position="end">
                  <IconButton
                    aria-label={showPassword ? '隐藏密码' : '显示密码'}
                    edge="end"
                    onClick={() => setShowPassword((value) => !value)}
                  >
                    {showPassword ? <VisibilityOffIcon /> : <VisibilityIcon />}
                  </IconButton>
                </InputAdornment>
              ),
            }}
          />
          <Button
            type="submit"
            variant="contained"
            size="large"
            startIcon={submitting ? <CircularProgress size={16} color="inherit" /> : <LoginIcon />}
            disabled={submitting}
            sx={{ minHeight: 46, width: { xs: '100%', sm: 'auto' } }}
          >
            {submitting ? '登录中...' : '登录'}
          </Button>
        </Stack>
      </Paper>
    </Box>
  );
}
