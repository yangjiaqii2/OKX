import LinkOffIcon from '@mui/icons-material/LinkOff';
import SaveIcon from '@mui/icons-material/Save';
import VerifiedIcon from '@mui/icons-material/Verified';
import VisibilityIcon from '@mui/icons-material/Visibility';
import VisibilityOffIcon from '@mui/icons-material/VisibilityOff';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  IconButton,
  InputAdornment,
  Stack,
  TextField,
} from '@mui/material';
import { FormEvent, useEffect, useState } from 'react';
import { Confirm, useNotify } from 'react-admin';
import { getAuthUser } from '../api/auth';
import { quantApi } from '../api/quantApi';
import { PanelState } from '../components/PanelState';
import { panel } from '../components/surfaces';
import { PageHeader, PageShell } from '../components/PageShell';
import { TradeMetricCard } from '../components/TradeMetricCard';
import { StatusChip } from '../components/StatusChip';
import { t, useLanguage } from '../i18n';

type BindingStatus = {
  bound?: boolean;
  maskedApiKey?: string;
  tradingMode?: 'DISABLED' | 'DEMO' | 'LIVE';
};

type VerificationResult = {
  ok?: boolean;
  mode?: string;
  message?: string;
  equity?: string | number;
  availableBalance?: string | number | null;
};

const emptyForm = {
  apiKey: '',
  secret: '',
  passphrase: '',
};

export function AccountBindingPage() {
  useLanguage();
  const notify = useNotify();
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [status, setStatus] = useState<BindingStatus>({});
  const [verification, setVerification] = useState<VerificationResult | null>(null);
  const [form, setForm] = useState(emptyForm);
  const [unbindOpen, setUnbindOpen] = useState(false);
  const [showSecrets, setShowSecrets] = useState(false);
  const username = getAuthUser() || t("当前用户");

  async function load() {
    setLoading(true);
    try {
      setStatus((await quantApi.accountBindingStatus()) as BindingStatus);
    } catch (error) {
      notify(error instanceof Error ? error.message : t("加载绑定状态失败"), { type: 'error' });
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
      notify(t("OKX账号已绑定，请点击“验证接口”确认API可用"), { type: 'success' });
    } catch (error) {
      notify(error instanceof Error ? error.message : t("绑定失败"), { type: 'error' });
    } finally {
      setSubmitting(false);
    }
  }

  async function verify() {
    setSubmitting(true);
    setVerification(null);
    try {
      const result = (await quantApi.verifyOkxAccount()) as VerificationResult;
      setVerification(result);
      notify(result.ok ? t("OKX接口验证通过") : String(result.message ?? t("OKX接口验证失败")), {
        type: result.ok ? 'success' : 'warning',
      });
    } catch (error) {
      notify(error instanceof Error ? error.message : t("OKX接口验证失败"), { type: 'error' });
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
      notify(t("OKX账号已解绑"), { type: 'success' });
    } catch (error) {
      notify(error instanceof Error ? error.message : t("解绑失败"), { type: 'error' });
    } finally {
      setSubmitting(false);
      setUnbindOpen(false);
    }
  }

  if (loading) {
    return <PageShell title={t("账号绑定")} maxWidth={1040}><PanelState loading sx={panel} /></PageShell>;
  }

  return (
    <PageShell title={t("账号绑定")} maxWidth={1040}>
      <Stack spacing={3}>
        <PageHeader
          title={t("OKX账号绑定")}
          subtitle={`${t("当前绑定对象：")}${username}${t("。API Key会保存到后端数据库并按登录用户隔离；Secret和Passphrase不会回显。绑定后请立即验证接口，确认读取权限、交易权限和IP白名单正确。")}`}
          status={<StatusChip value={Boolean(status.bound)} />}
        />

        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: 'minmax(0, 1fr)', sm: 'repeat(3, minmax(0, 1fr))' },
            gap: 2,
          }}
        >
          <TradeMetricCard label={t("绑定状态")} value={<StatusChip value={Boolean(status.bound)} />} />
          <TradeMetricCard label="API Key" value={status.maskedApiKey || '-'} helper={t("Secret和Passphrase不会回显")} />
          <TradeMetricCard
            label={t("交易模式")}
            value={status.tradingMode === 'LIVE' ? t("LIVE · 真实资金") : status.tradingMode === 'DEMO' ? t("DEMO · 模拟交易") : t("DISABLED · 已关闭")}
            helper={t("交易请求严格按后端单一 TradingMode 执行")}
          />
        </Box>

        {verification && (
          <Alert severity={verification.ok ? 'success' : 'error'}>
            {verification.message}
            {verification.ok ? `${t(" 可用余额：")}${verification.availableBalance ?? t("不可用")}` : ''}
          </Alert>
        )}
        <Alert severity="warning">
          {t("OKX返回401时，优先检查三项：API Key/Secret/Passphrase是否属于同一个API，API是否开启读取和交易权限，IP白名单是否包含当前后端服务器出口IP。生产环境请为数据库和后端配置访问控制。")}
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
                type={showSecrets ? 'text' : 'password'}
                value={form.secret}
                autoComplete="new-password"
                onChange={(event) => setForm((current) => ({ ...current, secret: event.target.value }))}
                InputProps={{
                  endAdornment: (
                    <InputAdornment position="end">
                      <IconButton
                        aria-label={showSecrets ? t("隐藏密钥") : t("显示密钥")}
                        edge="end"
                        onClick={() => setShowSecrets((value) => !value)}
                      >
                        {showSecrets ? <VisibilityOffIcon /> : <VisibilityIcon />}
                      </IconButton>
                    </InputAdornment>
                  ),
                }}
              />
              <TextField
                required
                label="Passphrase"
                type={showSecrets ? 'text' : 'password'}
                value={form.passphrase}
                autoComplete="new-password"
                onChange={(event) => setForm((current) => ({ ...current, passphrase: event.target.value }))}
              />
              <Stack direction={{ xs: 'column', sm: 'row' }} gap={1.5}>
                <Button type="submit" variant="contained" startIcon={<SaveIcon />} disabled={submitting} sx={{ width: { xs: '100%', sm: 'auto' } }}>
                  {t("绑定账号")}
                </Button>
                <Button
                  type="button"
                  variant="outlined"
                  startIcon={<VerifiedIcon />}
                  disabled={submitting || !status.bound}
                  onClick={() => void verify()}
                  sx={{ width: { xs: '100%', sm: 'auto' } }}
                >
                  {t("验证接口")}
                </Button>
                <Button
                  type="button"
                  variant="outlined"
                  color="warning"
                  startIcon={<LinkOffIcon />}
                  disabled={submitting || !status.bound}
                  onClick={() => setUnbindOpen(true)}
                  sx={{ width: { xs: '100%', sm: 'auto' } }}
                >
                  {t("解绑")}
                </Button>
              </Stack>
            </Stack>
          </CardContent>
        </Card>
        <Confirm
          isOpen={unbindOpen}
          title={t("解绑 OKX 账号")}
          content={t("解绑后前端不会再显示当前 OKX API 绑定状态，私有交易接口需要重新绑定后才能验证。")}
          confirm={t("解绑")}
          confirmColor="warning"
          cancel={t("返回")}
          onConfirm={() => void unbind()}
          onClose={() => setUnbindOpen(false)}
        />
      </Stack>
    </PageShell>
  );
}
