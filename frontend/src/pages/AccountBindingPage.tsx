import LinkOffIcon from '@mui/icons-material/LinkOff';
import SaveIcon from '@mui/icons-material/Save';
import VerifiedIcon from '@mui/icons-material/Verified';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { FormEvent, useEffect, useState } from 'react';
import { Loading, useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { PageHeader, PageShell } from '../components/PageShell';
import { MetricCard } from '../components/MetricCard';
import { StatusChip } from '../components/StatusChip';

type BindingStatus = {
  bound?: boolean;
  maskedApiKey?: string;
  liveTradingEnabled?: boolean;
};

type VerificationResult = {
  ok?: boolean;
  mode?: string;
  message?: string;
  equity?: string | number;
  availableBalance?: string | number;
};

const emptyForm = {
  apiKey: '',
  secret: '',
  passphrase: '',
};

export function AccountBindingPage() {
  const notify = useNotify();
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [status, setStatus] = useState<BindingStatus>({});
  const [verification, setVerification] = useState<VerificationResult | null>(null);
  const [form, setForm] = useState(emptyForm);

  async function load() {
    setLoading(true);
    try {
      setStatus((await quantApi.accountBindingStatus()) as BindingStatus);
    } catch (error) {
      notify(error instanceof Error ? error.message : '加载绑定状态失败', { type: 'error' });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function bind(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setVerification(null);
    try {
      const nextStatus = (await quantApi.bindOkxAccount(form)) as BindingStatus;
      setStatus(nextStatus);
      setForm(emptyForm);
      notify('OKX账号已绑定，请点击“验证接口”确认API可用', { type: 'success' });
    } catch (error) {
      notify(error instanceof Error ? error.message : '绑定失败', { type: 'error' });
    } finally {
      setSubmitting(false);
    }
  }

  async function verify() {
    setSubmitting(true);
    try {
      const result = (await quantApi.verifyOkxAccount()) as VerificationResult;
      setVerification(result);
      notify(result.ok ? 'OKX接口验证通过' : String(result.message ?? 'OKX接口验证失败'), {
        type: result.ok ? 'success' : 'warning',
      });
    } catch (error) {
      notify(error instanceof Error ? error.message : 'OKX接口验证失败', { type: 'error' });
    } finally {
      setSubmitting(false);
    }
  }

  async function unbind() {
    setSubmitting(true);
    try {
      const nextStatus = (await quantApi.unbindOkxAccount()) as BindingStatus;
      setStatus(nextStatus);
      setVerification(null);
      setForm(emptyForm);
      notify('OKX账号已解绑', { type: 'success' });
    } catch (error) {
      notify(error instanceof Error ? error.message : '解绑失败', { type: 'error' });
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) {
    return <Loading />;
  }

  return (
    <PageShell title="账号绑定" maxWidth={1040}>
      <Stack spacing={2.5} maxWidth={980}>
        <PageHeader
          title="OKX账号绑定"
          subtitle="API Key会保存到后端数据库，后端重启后仍可恢复绑定状态。Secret和Passphrase不会回显；绑定后请立即验证接口，确认读取权限和IP白名单正确。"
          eyebrow="Credential Vault"
          status={<StatusChip value={Boolean(status.bound)} />}
        />

        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(0, 1fr))' },
            gap: 2,
          }}
        >
          <MetricCard label="绑定状态" value={<StatusChip value={Boolean(status.bound)} />} />
          <MetricCard label="API Key" value={status.maskedApiKey || '-'} helper="Secret和Passphrase不会回显" />
          <MetricCard
            label="交易模式"
            value={status.liveTradingEnabled ? '实盘' : '未开启'}
            helper="确认订单会调用OKX实盘接口，不再生成本地成交记录"
          />
        </Box>

        {verification && (
          <Alert severity={verification.ok ? 'success' : 'error'}>
            {verification.message}
            {verification.ok ? ` 可用余额：${String(verification.availableBalance ?? '-')}` : ''}
          </Alert>
        )}
        <Alert severity="warning">
          OKX返回401时，优先检查三项：API Key/Secret/Passphrase是否属于同一个API，API是否开启读取和交易权限，IP白名单是否包含当前后端服务器出口IP。生产环境请为数据库和后端配置访问控制。
        </Alert>

        <Card>
          <CardContent>
            <Stack component="form" spacing={2} onSubmit={(event) => void bind(event)}>
              <TextField
                required
                label="API Key"
                value={form.apiKey}
                autoComplete="off"
                onChange={(event) => setForm((current) => ({ ...current, apiKey: event.target.value }))}
              />
              <TextField
                required
                label="Secret"
                type="password"
                value={form.secret}
                autoComplete="new-password"
                onChange={(event) => setForm((current) => ({ ...current, secret: event.target.value }))}
              />
              <TextField
                required
                label="Passphrase"
                type="password"
                value={form.passphrase}
                autoComplete="new-password"
                onChange={(event) => setForm((current) => ({ ...current, passphrase: event.target.value }))}
              />
              <Stack direction={{ xs: 'column', sm: 'row' }} gap={1.5}>
                <Button type="submit" variant="contained" startIcon={<SaveIcon />} disabled={submitting}>
                  绑定账号
                </Button>
                <Button
                  type="button"
                  variant="outlined"
                  startIcon={<VerifiedIcon />}
                  disabled={submitting || !status.bound}
                  onClick={() => void verify()}
                >
                  验证接口
                </Button>
                <Button
                  type="button"
                  variant="outlined"
                  color="warning"
                  startIcon={<LinkOffIcon />}
                  disabled={submitting || !status.bound}
                  onClick={() => void unbind()}
                >
                  解绑
                </Button>
              </Stack>
            </Stack>
          </CardContent>
        </Card>
      </Stack>
    </PageShell>
  );
}
