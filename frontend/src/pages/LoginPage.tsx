import { useReducedMotion } from 'motion/react';
import { motion } from 'motion/react';
import { FormEvent, useState } from 'react';
import { useLogin, useNotify } from 'react-admin';
import { LanguageSwitch } from '../components/LanguageSwitch';
import { ThemeToggle } from '../components/ThemeToggle';
import { Button } from '../components/ui/button';
import { Field, Input } from '../components/ui/field';
import { t, useLanguage } from '../i18n';

export function LoginPage() {
  useLanguage();
  const reduce = useReducedMotion();
  const login = useLogin();
  const notify = useNotify();
  const [submitting, setSubmitting] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [form, setForm] = useState({ username: '', password: '' });
  const [touched, setTouched] = useState({ username: false, password: false });
  const [errorMessage, setErrorMessage] = useState('');

  const usernameError = touched.username && !form.username.trim() ? t('请输入账号') : '';
  const passwordError = touched.password && !form.password ? t('请输入密码') : '';

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setTouched({ username: true, password: true });
    const username = form.username.trim();
    if (!username || !form.password) {
      setErrorMessage(!username ? '请输入账号' : '请输入密码');
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
      notify(message || t('登录失败'), { type: 'error' });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex min-h-dvh flex-col bg-canvas px-6 py-8 text-ink sm:px-16 lg:px-20">
      <div className="flex items-center justify-end gap-3">
        <ThemeToggle />
        <LanguageSwitch />
      </div>
      <div className="flex flex-1 items-center">
        <motion.div
          className="grid w-full items-center gap-12 lg:grid-cols-[400px_minmax(0,1fr)] lg:gap-32"
          initial={reduce ? false : { opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}
        >
          <div>
            <div className="mb-7 flex items-center gap-2.5">
              <i className="size-2.5 rounded-[3px] bg-accent" />
              <b className="text-[15px] font-semibold tracking-[-0.024em]">Quant Desk</b>
            </div>
            <h1 className="text-[36px] font-semibold leading-[44px] tracking-[-0.03em]">{t('登录')}</h1>
            <p className="mt-2 max-w-[360px] text-[15px] leading-6 text-muted">{t('使用已创建的系统账号进入控制台。')}</p>
            <form className="mt-7" onSubmit={(event) => void submit(event)} noValidate>
              {errorMessage ? <p className="mb-4 text-[13px] leading-5 text-down" role="alert">{t(errorMessage)}</p> : null}
              <Field label={t('用户名')} error={usernameError}>
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
                    autoComplete="current-password"
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
                    {showPassword ? t('隐藏密码') : t('显示密码')}
                  </button>
                </div>
              </Field>
              <Button className="mt-5 w-full" variant="primary" size="md" type="submit" disabled={submitting}>
                {submitting ? t('登录中...') : t('登录')}
              </Button>
            </form>
            <p className="mt-3.5 text-[13px] leading-5 text-muted">{t('凭据经安全哈希保存。登录后可在账号安全中修改密码。')}</p>
          </div>
          <div className="max-w-[520px]">
            <h2 className="text-[40px] font-semibold leading-[52px] tracking-[-0.035em] sm:text-[56px] sm:leading-[68px]">
              {t('账户与风控')}<br />{t('优先于下单')}
            </h2>
            <p className="mt-5 max-w-[420px] text-[16px] leading-[26px] text-muted">
              {t('确认订单之前，先核对权益、可用余额和风控状态。实盘与模拟使用同一套控制台。')}
            </p>
          </div>
        </motion.div>
      </div>
    </div>
  );
}
