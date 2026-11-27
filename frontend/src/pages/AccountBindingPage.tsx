import { FormEvent, useEffect, useState } from 'react';
import { useNotify } from 'react-admin';
import { getAuthUser } from '../api/auth';
import { quantApi } from '../api/quantApi';
import { PageHeader, PageShell } from '../components/PageShell';
import { PanelState } from '../components/PanelState';
import { StatusChip } from '../components/StatusChip';
import { Alert } from '../components/ui/alert';
import { Button } from '../components/ui/button';
import { ConfirmDialog } from '../components/ui/confirm-dialog';
import { Field, Input } from '../components/ui/field';
import { t, useLanguage } from '../i18n';

type BindingStatus = { bound?: boolean; maskedApiKey?: string; tradingMode?: 'DISABLED' | 'DEMO' | 'LIVE' };
type VerificationResult = { ok?: boolean; mode?: string; message?: string; equity?: string | number; availableBalance?: string | number | null };
const emptyForm = { apiKey: '', secret: '', passphrase: '' };

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
  const username = getAuthUser() || t('当前用户');

  async function load() {
    setLoading(true);
    try {
      setStatus((await quantApi.accountBindingStatus()) as BindingStatus);
    } catch (error) {
      notify(error instanceof Error ? error.message : t('加载绑定状态失败'), { type: 'error' });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  async function bind(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setVerification(null);
    try {
      const nextStatus = (await quantApi.bindOkxAccount(form)) as BindingStatus;
      setStatus(nextStatus);
      setForm(emptyForm);
      notify(t('OKX账号已绑定，请点击“验证接口”确认API可用'), { type: 'success' });
    } catch (error) {
      notify(error instanceof Error ? error.message : t('绑定失败'), { type: 'error' });
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
      notify(result.ok ? t('OKX接口验证通过') : String(result.message ?? t('OKX接口验证失败')), { type: result.ok ? 'success' : 'warning' });
    } catch (error) {
      notify(error instanceof Error ? error.message : t('OKX接口验证失败'), { type: 'error' });
    } finally {
      setSubmitting(false);
    }
  }

  async function unbind() {
    setSubmitting(true);
    try {
      setStatus((await quantApi.unbindOkxAccount()) as BindingStatus);
      setVerification(null);
      setForm(emptyForm);
      notify(t('OKX账号已解绑'), { type: 'success' });
    } catch (error) {
      notify(error instanceof Error ? error.message : t('解绑失败'), { type: 'error' });
    } finally {
      setSubmitting(false);
      setUnbindOpen(false);
    }
  }

  if (loading) return <PageShell title={t('账号绑定')} maxWidth={720}><PanelState loading /></PageShell>;

  return (
    <PageShell title={t('账号绑定')} maxWidth={720}>
      <PageHeader
        title={t('OKX账号绑定')}
        subtitle={`${t('当前绑定对象：')}${username}${t('。API Key会保存到后端数据库并按登录用户隔离；Secret和Passphrase不会回显。绑定后请立即验证接口，确认读取权限、交易权限和IP白名单正确。')}`}
        status={<StatusChip value={Boolean(status.bound)} />}
      />
      <div className="mt-6 grid gap-6 sm:grid-cols-3">
        <Stat label={t('绑定状态')} value={status.bound ? t('已绑定') : t('未绑定')} />
        <Stat label="API Key" value={status.maskedApiKey || '-'} hint={t('Secret和Passphrase不会回显')} />
        <Stat label={t('交易模式')} value={status.tradingMode === 'LIVE' ? t('LIVE · 真实资金') : status.tradingMode === 'DEMO' ? t('DEMO · 模拟交易') : t('DISABLED · 已关闭')} />
      </div>
      <div className="mt-4 space-y-3">
        {verification ? (
          <Alert severity={verification.ok ? 'success' : 'error'}>
            {verification.message}
            {verification.ok ? `${t(' 可用余额：')}${verification.availableBalance ?? t('不可用')}` : ''}
          </Alert>
        ) : null}
        <Alert severity="warning">{t('OKX返回401时，优先检查三项：API Key/Secret/Passphrase是否属于同一个API，API是否开启读取和交易权限，IP白名单是否包含当前后端服务器出口IP。生产环境请为数据库和后端配置访问控制。')}</Alert>
      </div>
      <form className="mt-6 max-w-[520px] space-y-4" onSubmit={(event) => void bind(event)}>
        <Field label="API Key"><Input required autoComplete="off" value={form.apiKey} onChange={(event) => setForm((current) => ({ ...current, apiKey: event.target.value }))} /></Field>
        <Field label="Secret">
          <div className="relative">
            <Input required className="pr-16" type={showSecrets ? 'text' : 'password'} autoComplete="new-password" value={form.secret} onChange={(event) => setForm((current) => ({ ...current, secret: event.target.value }))} />
            <button type="button" className="absolute right-3 top-1/2 -translate-y-1/2 text-[13px] text-muted" aria-label={showSecrets ? t('隐藏密钥') : t('显示密钥')} onClick={() => setShowSecrets((value) => !value)}>{showSecrets ? t('隐藏密码') : t('显示密码')}</button>
          </div>
        </Field>
        <Field label="Passphrase"><Input required type={showSecrets ? 'text' : 'password'} autoComplete="new-password" value={form.passphrase} onChange={(event) => setForm((current) => ({ ...current, passphrase: event.target.value }))} /></Field>
        <div className="flex flex-wrap gap-2">
          <Button variant="primary" type="submit" disabled={submitting}>{t('绑定账号')}</Button>
          <Button variant="ghost" type="button" disabled={submitting || !status.bound} onClick={() => void verify()}>{t('验证接口')}</Button>
          <Button variant="warning" type="button" disabled={submitting || !status.bound} onClick={() => setUnbindOpen(true)}>{t('解绑')}</Button>
        </div>
      </form>
      <ConfirmDialog
        open={unbindOpen}
        title={t('解绑 OKX 账号')}
        content={t('解绑后前端不会再显示当前 OKX API 绑定状态，私有交易接口需要重新绑定后才能验证。')}
        confirmLabel={t('解绑')}
        cancelLabel={t('返回')}
        tone="warning"
        pending={submitting}
        onConfirm={() => void unbind()}
        onClose={() => setUnbindOpen(false)}
      />
    </PageShell>
  );
}

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div>
      <div className="text-[12px] font-medium text-muted">{label}</div>
      <div className="mt-1 break-all text-[18px] font-medium leading-6">{value}</div>
      {hint ? <div className="mt-1 text-[12px] text-faint">{hint}</div> : null}
    </div>
  );
}
