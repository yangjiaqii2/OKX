import { ArrowLeft, Check, ShieldCheck } from 'lucide-react';
import { motion, useReducedMotion } from 'motion/react';
import { FormEvent, useEffect, useState } from 'react';
import { useLogin, useNotify } from 'react-admin';
import { useLocation, useNavigate } from 'react-router-dom';
import { fetchRegistrationConfig, registerAccount, type RegistrationConfig } from '../api/authProvider';
import { BrandMark } from '../components/BrandMark';
import { LanguageSwitch } from '../components/LanguageSwitch';
import { ThemeToggle } from '../components/ThemeToggle';
import { Button } from '../components/ui/button';
import { Field, Input } from '../components/ui/field';
import { t, useLanguage } from '../i18n';
import { cn } from '../lib/cn';

type AuthMode = 'login' | 'register';

type LoginPageProps = {
  initialMode?: AuthMode;
  onBackHome?: () => void;
  onAuthSuccess?: () => void;
};

export function LoginPage({ initialMode, onBackHome, onAuthSuccess }: LoginPageProps = {}) {
  useLanguage();
  const reduce = useReducedMotion();
  const login = useLogin();
  const notify = useNotify();
  const navigate = useNavigate();
  const location = useLocation();
  const queryMode = new URLSearchParams(location.search).get('mode') === 'register' ? 'register' : 'login';
  const [mode, setMode] = useState<AuthMode>(initialMode ?? queryMode);
  const [regConfig, setRegConfig] = useState<RegistrationConfig>({
    registrationEnabled: true,
    inviteCodeRequired: false,
  });
  const [submitting, setSubmitting] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [form, setForm] = useState({ username: '', password: '', confirmPassword: '', inviteCode: '' });
  const [touched, setTouched] = useState({ username: false, password: false, confirmPassword: false, inviteCode: false });
  const [errorMessage, setErrorMessage] = useState('');

  useEffect(() => {
    if (initialMode) {
      setMode(initialMode);
    }
  }, [initialMode]);

  useEffect(() => {
    let active = true;
    void fetchRegistrationConfig().then((config) => {
      if (!active) return;
      setRegConfig(config);
      if (!config.registrationEnabled) {
        setMode('login');
      }
    });
    return () => {
      active = false;
    };
  }, []);

  const isRegister = mode === 'register' && regConfig.registrationEnabled;
  const usernameError = touched.username && !form.username.trim() ? t('请输入账号') : '';
  const passwordError = touched.password && !form.password
    ? t('请输入密码')
    : isRegister && touched.password && form.password.length < 6
      ? t('密码长度不能少于 6 位')
      : '';
  const confirmPasswordError = isRegister && touched.confirmPassword && form.confirmPassword !== form.password
    ? t('两次输入的密码不一致')
    : '';
  const inviteCodeError = isRegister && regConfig.inviteCodeRequired && touched.inviteCode && !form.inviteCode.trim()
    ? t('请输入邀请码')
    : '';

  function switchMode(nextMode: AuthMode) {
    setMode(nextMode);
    setErrorMessage('');
    setTouched({ username: false, password: false, confirmPassword: false, inviteCode: false });
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setTouched({ username: true, password: true, confirmPassword: true, inviteCode: true });
    const username = form.username.trim();
    if (!username || !form.password) {
      setErrorMessage(!username ? '请输入账号' : '请输入密码');
      return;
    }
    if (isRegister) {
      if (form.password.length < 6) {
        setErrorMessage('密码长度不能少于 6 位');
        return;
      }
      if (form.confirmPassword !== form.password) {
        setErrorMessage('两次输入的密码不一致');
        return;
      }
      if (regConfig.inviteCodeRequired && !form.inviteCode.trim()) {
        setErrorMessage('请输入邀请码');
        return;
      }
    }
    setSubmitting(true);
    setErrorMessage('');
    try {
      if (isRegister) {
        await registerAccount({
          username,
          password: form.password,
          inviteCode: form.inviteCode.trim(),
        });
        notify(t('注册成功，请绑定 OKX 密钥并配置大模型 API'), { type: 'success' });
        if (onAuthSuccess) onAuthSuccess();
        else navigate('/', { replace: true });
      } else {
        await login({ username, password: form.password });
        if (onAuthSuccess) onAuthSuccess();
      }
    } catch (error) {
      const fallback = isRegister ? '注册失败' : '账号或密码错误';
      const message = error instanceof Error ? error.message : fallback;
      setErrorMessage(message || fallback);
      setForm((current) => ({ ...current, password: '', confirmPassword: '' }));
      notify(message || t(fallback), { type: 'error' });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex min-h-dvh flex-col bg-canvas px-6 py-6 text-ink sm:px-12 lg:px-16">
      <div className="mx-auto flex w-full max-w-[1220px] items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          {onBackHome ? (
            <button
              type="button"
              onClick={onBackHome}
              className="inline-flex items-center gap-1.5 rounded-full border border-line-strong bg-raised px-3.5 py-1.5 text-[13px] font-medium text-ink transition-colors hover:bg-sunken"
            >
              <ArrowLeft size={14} />
              {t('返回首页')}
            </button>
          ) : (
            <div className="flex items-center gap-2.5">
              <BrandMark tone="forest" size={26} />
              <b className="font-serif text-[17px] font-medium tracking-[-0.02em]">Quant Desk</b>
            </div>
          )}
        </div>
        <div className="flex items-center gap-3">
          <ThemeToggle />
          <LanguageSwitch />
        </div>
      </div>

      <div className="mx-auto flex w-full max-w-[1220px] flex-1 items-center py-8">
        <motion.div
          className="grid w-full items-center gap-10 lg:grid-cols-[420px_minmax(0,1fr)] lg:gap-16"
          initial={reduce ? false : { opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}
        >
          <div className="rounded-[28px] border border-line bg-raised p-7 shadow-[0_10px_36px_rgba(19,38,27,0.05)] sm:p-9">
            <div className="mb-6 flex items-center gap-2.5">
              <BrandMark tone="forest" size={26} />
              <b className="font-serif text-[17px] font-medium tracking-[-0.02em]">Quant Desk</b>
            </div>

            {regConfig.registrationEnabled ? (
              <div className="mb-5 inline-flex rounded-full bg-sunken p-1" role="tablist" aria-label={t('账号入口')}>
                {(['login', 'register'] as const).map((tab) => (
                  <button
                    key={tab}
                    type="button"
                    role="tab"
                    aria-selected={mode === tab}
                    className={cn(
                      'h-8 min-w-20 rounded-full px-4 text-[13px] font-medium transition-colors',
                      mode === tab
                        ? 'bg-raised text-ink shadow-[0_1px_2px_rgba(19,38,27,0.08)]'
                        : 'text-muted hover:text-ink',
                    )}
                    onClick={() => switchMode(tab)}
                  >
                    {tab === 'login' ? t('登录') : t('注册')}
                  </button>
                ))}
              </div>
            ) : null}

            <h1 className="font-serif text-[34px] font-normal leading-[40px] tracking-[-0.02em]">
              {isRegister ? t('创建账号') : t('欢迎回来')}
            </h1>
            <p className="mt-2 text-[14px] leading-6 text-muted">
              {isRegister
                ? t('注册个人账号后，可绑定自己的 OKX 密钥与大模型 API。')
                : t('使用已创建的系统账号进入控制台。')}
            </p>

            <form className="mt-6" onSubmit={(event) => void submit(event)} noValidate>
              {errorMessage ? <p className="mb-4 text-[13px] leading-5 text-down" role="alert">{t(errorMessage)}</p> : null}
              <Field label={t('用户名')} error={usernameError} hint={isRegister ? t('3-32 位字母、数字、点、下划线或短横线') : undefined}>
                <Input
                  required
                  name="username"
                  autoComplete="username"
                  autoFocus
                  value={form.username}
                  disabled={submitting}
                  aria-invalid={Boolean(usernameError)}
                  onBlur={() => setTouched((current) => ({ ...current, username: true }))}
                  onChange={(event) => setForm((current) => ({ ...current, username: event.target.value }))}
                />
              </Field>
              <Field className="mt-4" label={t('密码')} error={passwordError}>
                <div className="relative">
                  <Input
                    required
                    name="password"
                    type={showPassword ? 'text' : 'password'}
                    autoComplete={isRegister ? 'new-password' : 'current-password'}
                    value={form.password}
                    disabled={submitting}
                    aria-invalid={Boolean(passwordError)}
                    className="pr-16"
                    onBlur={() => setTouched((current) => ({ ...current, password: true }))}
                    onChange={(event) => setForm((current) => ({ ...current, password: event.target.value }))}
                  />
                  <button
                    type="button"
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-[13px] font-medium text-muted"
                    aria-label={showPassword ? t('隐藏密码') : t('显示密码')}
                    onClick={() => setShowPassword((value) => !value)}
                  >
                    {showPassword ? t('隐藏') : t('显示')}
                  </button>
                </div>
              </Field>
              {isRegister ? (
                <Field className="mt-4" label={t('确认密码')} error={confirmPasswordError}>
                  <Input
                    required
                    name="confirmPassword"
                    type={showPassword ? 'text' : 'password'}
                    autoComplete="new-password"
                    value={form.confirmPassword}
                    disabled={submitting}
                    aria-invalid={Boolean(confirmPasswordError)}
                    onBlur={() => setTouched((current) => ({ ...current, confirmPassword: true }))}
                    onChange={(event) => setForm((current) => ({ ...current, confirmPassword: event.target.value }))}
                  />
                </Field>
              ) : null}
              {isRegister && regConfig.inviteCodeRequired ? (
                <Field className="mt-4" label={t('邀请码')} error={inviteCodeError}>
                  <Input
                    required
                    name="inviteCode"
                    autoComplete="off"
                    value={form.inviteCode}
                    disabled={submitting}
                    aria-invalid={Boolean(inviteCodeError)}
                    onBlur={() => setTouched((current) => ({ ...current, inviteCode: true }))}
                    onChange={(event) => setForm((current) => ({ ...current, inviteCode: event.target.value }))}
                  />
                </Field>
              ) : null}
              <Button className="mt-6 w-full" variant="primary" size="md" type="submit" disabled={submitting}>
                {submitting
                  ? (isRegister ? t('注册中...') : t('登录中...'))
                  : (isRegister ? t('注册并进入控制台') : t('登录'))}
              </Button>
            </form>
            <p className="mt-4 text-[12px] leading-5 text-muted">
              {isRegister
                ? t('新注册账号默认为普通用户，数据与密钥按账号严格隔离。')
                : t('凭据经安全哈希保存。登录后可在账号安全中修改密码。')}
            </p>
          </div>

          {/* Right Atmospheric Editorial Forest Panel (Harmonized with Landing Hero) */}
          <div className="relative hidden overflow-hidden rounded-[28px] bg-[#143528] p-10 text-white lg:flex lg:min-h-[540px] lg:flex-col lg:justify-between">
            <div
              className="pointer-events-none absolute inset-0"
              style={{
                background:
                  'radial-gradient(65% 65% at 75% 25%, rgba(92, 148, 114, 0.5) 0%, rgba(24, 64, 47, 0.88) 55%, rgba(12, 29, 21, 0.98) 100%)',
              }}
            />
            <div className="grain-overlay pointer-events-none absolute inset-0 opacity-40 mix-blend-overlay" />

            <div className="relative z-10">
              <span className="inline-flex items-center gap-2 rounded-full border border-white/15 bg-white/10 px-3 py-1 text-[12px] font-medium text-white/85">
                <span className="size-1.5 rounded-full bg-[#D6EE8A]" />
                {t('账户与风控优先于下单')}
              </span>
              <h2 className="mt-6 font-serif text-[44px] font-normal leading-[1.15] tracking-[-0.02em] text-white">
                {t('把纪律交给系统，')}<br />{t('把判断留给自己。')}
              </h2>
              <p className="mt-4 max-w-[420px] text-[15px] leading-[1.7] text-white/70">
                {t('确认订单之前，先核对权益、可用余额和风控状态。OKX 密钥与个人大模型 API 均按用户 AES-GCM 加密隔离。')}
              </p>
            </div>

            <div className="relative z-10 mt-8 grid gap-3 sm:grid-cols-2">
              <div className="rounded-[20px] bg-white/95 p-4 text-[#17211B] shadow-[0_12px_30px_rgba(0,0,0,0.18)]">
                <div className="flex items-center gap-2 text-[12px] font-semibold text-[#13261B]">
                  <span className="grid size-5 place-items-center rounded-full bg-[#13261B] text-[#D6EE8A]">
                    <ShieldCheck size={12} />
                  </span>
                  OKX API · AES-GCM
                </div>
                <p className="mt-2 text-[12px] leading-5 text-[#5A635D]">
                  {t('支持 DEMO 模拟盘与 LIVE 实盘，密钥永不回显明文。')}
                </p>
              </div>
              <div className="rounded-[20px] border border-[#13261B]/20 bg-[#D6EE8A] p-4 text-[#13261B] shadow-[0_12px_30px_rgba(0,0,0,0.18)]">
                <div className="flex items-center gap-2 text-[12px] font-semibold">
                  <span className="grid size-5 place-items-center rounded-full bg-[#13261B] text-[#D6EE8A]">
                    <Check size={12} strokeWidth={2.5} />
                  </span>
                  LLM API · BYOK
                </div>
                <p className="mt-2 text-[12px] leading-5 text-[#13261B]/80">
                  {t('自带 DeepSeek / OpenAI / Moonshot API Key，一键测试连通性。')}
                </p>
              </div>
            </div>
          </div>
        </motion.div>
      </div>
    </div>
  );
}
