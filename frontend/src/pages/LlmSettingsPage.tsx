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

type LlmProfile = {
  id: number;
  name: string;
  providerType: string;
  baseUrl: string;
  modelName: string;
  maskedApiKey: string;
  active: boolean;
  updatedAt?: string;
};

type LlmStatus = {
  configured?: boolean;
  activeProfileId?: number | null;
  activeProfileName?: string | null;
  activeModelName?: string | null;
  activeMaskedApiKey?: string | null;
  profileCount?: number;
};

type TestResult = {
  ok?: boolean;
  model?: string;
  elapsedMs?: number;
  message?: string;
};

const PRESET_GROUPS = [
  {
    group: '国内主流',
    items: [
      { label: 'DeepSeek', name: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', modelName: 'deepseek-chat' },
      { label: '通义千问 Qwen', name: '通义千问 (百炼)', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', modelName: 'qwen-plus' },
      { label: 'Kimi / Moonshot', name: 'Kimi / Moonshot', baseUrl: 'https://api.moonshot.cn/v1', modelName: 'kimi-k2-0905-preview' },
      { label: '智谱 GLM', name: '智谱 GLM', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', modelName: 'glm-4-plus' },
      { label: '豆包 (火山方舟)', name: '豆包 Ark', baseUrl: 'https://ark.cn-beijing.volces.com/api/v3', modelName: 'doubao-1-5-pro-32k-250115' },
      { label: 'MiniMax', name: 'MiniMax', baseUrl: 'https://api.minimax.chat/v1', modelName: 'MiniMax-Text-01' },
      { label: '硅基流动', name: 'SiliconFlow', baseUrl: 'https://api.siliconflow.cn/v1', modelName: 'deepseek-ai/DeepSeek-V3' },
    ],
  },
  {
    group: '海外主流 & 聚合',
    items: [
      { label: 'OpenAI / ChatGPT', name: 'OpenAI', baseUrl: 'https://api.openai.com/v1', modelName: 'gpt-4o-mini' },
      { label: 'Google Gemini', name: 'Gemini (OpenAI 兼容)', baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai', modelName: 'gemini-2.5-flash' },
      { label: 'xAI Grok', name: 'xAI Grok', baseUrl: 'https://api.x.ai/v1', modelName: 'grok-3-mini' },
      { label: 'OpenRouter (Claude 等)', name: 'OpenRouter', baseUrl: 'https://openrouter.ai/api/v1', modelName: 'anthropic/claude-3.7-sonnet' },
    ],
  },
];

const emptyForm = {
  id: null as number | null,
  name: '',
  baseUrl: '',
  modelName: '',
  apiKey: '',
  active: true,
};

export function LlmSettingsPage() {
  useLanguage();
  const notify = useNotify();
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [testingId, setTestingId] = useState<number | null>(null);
  const [status, setStatus] = useState<LlmStatus>({});
  const [profiles, setProfiles] = useState<LlmProfile[]>([]);
  const [testResults, setTestResults] = useState<Record<number, TestResult>>({});
  const [form, setForm] = useState(emptyForm);
  const [showKey, setShowKey] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<LlmProfile | null>(null);
  const username = getAuthUser() || t('当前用户');

  async function load() {
    setLoading(true);
    try {
      const [nextStatus, nextProfiles] = await Promise.all([
        quantApi.llmProfileStatus() as Promise<LlmStatus>,
        quantApi.llmProfiles() as Promise<LlmProfile[]>,
      ]);
      setStatus(nextStatus ?? {});
      setProfiles(Array.isArray(nextProfiles) ? nextProfiles : []);
    } catch (error) {
      notify(error instanceof Error ? error.message : t('加载大模型配置失败'), { type: 'error' });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    try {
      await quantApi.saveLlmProfile({
        id: form.id,
        name: form.name.trim(),
        baseUrl: form.baseUrl.trim(),
        modelName: form.modelName.trim(),
        apiKey: form.apiKey.trim() || undefined,
        active: form.active,
      });
      notify(form.id ? t('大模型 API 配置已更新') : t('大模型 API 配置已保存'), { type: 'success' });
      setForm(emptyForm);
      await load();
    } catch (error) {
      notify(error instanceof Error ? error.message : t('保存大模型配置失败'), { type: 'error' });
    } finally {
      setSubmitting(false);
    }
  }

  async function activate(id: number) {
    setSubmitting(true);
    try {
      await quantApi.activateLlmProfile(id);
      notify(t('已切换默认大模型配置'), { type: 'success' });
      await load();
    } catch (error) {
      notify(error instanceof Error ? error.message : t('切换默认配置失败'), { type: 'error' });
    } finally {
      setSubmitting(false);
    }
  }

  async function testProfile(id: number) {
    setTestingId(id);
    try {
      const result = (await quantApi.testLlmProfile(id)) as TestResult;
      setTestResults((prev) => ({ ...prev, [id]: result }));
      notify(
        result.ok ? t('大模型连通性验证通过') : String(result.message ?? t('大模型连通性验证失败')),
        { type: result.ok ? 'success' : 'warning' },
      );
    } catch (error) {
      notify(error instanceof Error ? error.message : t('大模型连通性验证失败'), { type: 'error' });
    } finally {
      setTestingId(null);
    }
  }

  async function remove(profile: LlmProfile) {
    setSubmitting(true);
    try {
      await quantApi.deleteLlmProfile(profile.id);
      notify(t('大模型配置已删除'), { type: 'success' });
      if (form.id === profile.id) {
        setForm(emptyForm);
      }
      await load();
    } catch (error) {
      notify(error instanceof Error ? error.message : t('删除配置失败'), { type: 'error' });
    } finally {
      setSubmitting(false);
      setDeleteTarget(null);
    }
  }

  function startEdit(profile: LlmProfile) {
    setForm({
      id: profile.id,
      name: profile.name,
      baseUrl: profile.baseUrl,
      modelName: profile.modelName,
      apiKey: '',
      active: profile.active,
    });
  }

  if (loading) {
    return (
      <PageShell title={t('大模型 API')} maxWidth={860}>
        <PanelState loading />
      </PageShell>
    );
  }

  return (
    <PageShell title={t('大模型 API')} maxWidth={860}>
      <PageHeader
        title={t('个人大模型 API 配置')}
        subtitle={`${t('当前配置归属：')}${username}${t('。平台采用自带 API Key（BYOK）模式，支持任意 OpenAI 兼容接口。API Key 经 AES-GCM 加密存储且永不回显明文。')}`}
        status={<StatusChip value={Boolean(status.configured)} />}
      />

      <div className="mt-6 grid gap-6 sm:grid-cols-3">
        <Stat label={t('配置状态')} value={status.configured ? t('已配置') : t('未配置')} />
        <Stat label={t('当前默认模型')} value={status.activeModelName || '-'} hint={status.activeProfileName || undefined} />
        <Stat label="API Key" value={status.activeMaskedApiKey || '-'} hint={t('加密存储，仅展示脱敏片段')} />
      </div>

      <div className="mt-4">
        <Alert severity="info">
          {t('后续创建并运行个人交易 Agent 时，将优先使用此处启用的大模型 API；费用与速率限制归属你自己的 API 账号，互不影响。')}
        </Alert>
      </div>

      <div className="mt-7 grid gap-8 lg:grid-cols-[minmax(0,440px)_minmax(0,1fr)]">
        <form className="space-y-4" onSubmit={(event) => void submit(event)}>
          <div className="flex items-center justify-between">
            <h2 className="text-[15px] font-semibold">
              {form.id ? t('编辑大模型配置') : t('新增大模型配置')}
            </h2>
            {form.id ? (
              <button
                type="button"
                className="text-[12px] font-medium text-muted hover:text-ink"
                onClick={() => setForm(emptyForm)}
              >
                {t('取消编辑')}
              </button>
            ) : null}
          </div>

          <div className="space-y-3 rounded-[18px] border border-line bg-canvas p-3.5">
            {PRESET_GROUPS.map((group) => (
              <div key={group.group}>
                <div className="mb-1.5 text-[11px] font-semibold tracking-wide text-muted">{t(group.group)}</div>
                <div className="flex flex-wrap gap-1.5">
                  {group.items.map((preset) => (
                    <button
                      key={preset.label}
                      type="button"
                      className="rounded-full border border-line-strong bg-raised px-3 py-1 text-[12px] font-medium text-ink/85 transition-colors hover:border-accent hover:bg-accent-soft hover:text-accent-ink"
                      onClick={() => setForm((current) => ({
                        ...current,
                        name: preset.name,
                        baseUrl: preset.baseUrl,
                        modelName: preset.modelName,
                      }))}
                    >
                      {preset.label}
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>

          <Field label={t('配置名称')}>
            <Input
              required
              placeholder="DeepSeek-V3"
              value={form.name}
              onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
            />
          </Field>

          <Field label="API Base URL" hint={t('OpenAI 兼容接口地址，如 https://api.deepseek.com/v1')}>
            <Input
              required
              placeholder="https://api.deepseek.com/v1"
              value={form.baseUrl}
              onChange={(event) => setForm((current) => ({ ...current, baseUrl: event.target.value }))}
            />
          </Field>

          <Field label={t('模型名称')}>
            <Input
              required
              placeholder="deepseek-chat"
              value={form.modelName}
              onChange={(event) => setForm((current) => ({ ...current, modelName: event.target.value }))}
            />
          </Field>

          <Field
            label="API Key"
            hint={form.id ? t('编辑时留空则保留已加密保存的原 API Key') : t('仅用于你的个人 Agent 调用，加密落库')}
          >
            <div className="relative">
              <Input
                required={!form.id}
                className="pr-16"
                type={showKey ? 'text' : 'password'}
                autoComplete="new-password"
                placeholder={form.id ? t('留空保持不变') : 'sk-...'}
                value={form.apiKey}
                onChange={(event) => setForm((current) => ({ ...current, apiKey: event.target.value }))}
              />
              <button
                type="button"
                className="absolute right-3 top-1/2 -translate-y-1/2 text-[13px] text-muted"
                onClick={() => setShowKey((value) => !value)}
              >
                {showKey ? t('隐藏') : t('显示')}
              </button>
            </div>
          </Field>

          <label className="flex items-center gap-2 text-[13px] text-ink">
            <input
              type="checkbox"
              checked={form.active}
              onChange={(event) => setForm((current) => ({ ...current, active: event.target.checked }))}
            />
            {t('保存后设为默认启用')}
          </label>

          <div className="flex gap-2">
            <Button variant="primary" type="submit" disabled={submitting} aria-busy={submitting}>
              {submitting ? t('提交中') : form.id ? t('更新配置') : t('保存配置')}
            </Button>
          </div>
        </form>

        <div>
          <h2 className="text-[15px] font-semibold">{t('已保存的配置')} ({profiles.length})</h2>
          {profiles.length === 0 ? (
            <p className="mt-3 text-[13px] leading-6 text-muted">
              {t('尚未配置个人大模型 API。请先在左侧填写你的 API Key 并保存，随后可点击“测试连通性”验证。')}
            </p>
          ) : (
            <div className="mt-3 space-y-3">
              {profiles.map((profile) => {
                const testResult = testResults[profile.id];
                return (
                  <div key={profile.id} className="rounded-xl border border-line bg-field p-4">
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <span className="truncate text-[14px] font-semibold">{profile.name}</span>
                          {profile.active ? (
                            <span className="rounded-md bg-accent-soft px-2 py-0.5 text-[11px] font-semibold text-accent-ink">
                              {t('默认启用')}
                            </span>
                          ) : null}
                        </div>
                        <div className="mt-1 text-[12px] text-muted">{profile.modelName} · {profile.maskedApiKey}</div>
                        <div className="mt-0.5 truncate text-[12px] text-faint">{profile.baseUrl}</div>
                      </div>
                    </div>
                    {testResult ? (
                      <div className="mt-3">
                        <Alert severity={testResult.ok ? 'success' : 'error'}>
                          {testResult.message}
                          {testResult.elapsedMs != null ? ` (${testResult.elapsedMs}ms)` : ''}
                        </Alert>
                      </div>
                    ) : null}
                    <div className="mt-3 flex flex-wrap gap-2">
                      {!profile.active ? (
                        <Button variant="ghost" size="sm" type="button" disabled={submitting} onClick={() => void activate(profile.id)}>
                          {t('设为默认')}
                        </Button>
                      ) : null}
                      <Button
                        variant="ghost"
                        size="sm"
                        type="button"
                        disabled={testingId === profile.id}
                        onClick={() => void testProfile(profile.id)}
                      >
                        {testingId === profile.id ? t('测试中...') : t('测试连通性')}
                      </Button>
                      <Button variant="ghost" size="sm" type="button" onClick={() => startEdit(profile)}>
                        {t('编辑')}
                      </Button>
                      <Button variant="warning" size="sm" type="button" disabled={submitting} onClick={() => setDeleteTarget(profile)}>
                        {t('删除')}
                      </Button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>

      <ConfirmDialog
        open={Boolean(deleteTarget)}
        title={t('删除大模型配置')}
        content={deleteTarget ? `${t('确认删除配置「')}${deleteTarget.name}${t('」？删除后无法恢复。')}` : ''}
        confirmLabel={t('删除')}
        cancelLabel={t('返回')}
        tone="warning"
        pending={submitting}
        onConfirm={() => { if (deleteTarget) void remove(deleteTarget); }}
        onClose={() => setDeleteTarget(null)}
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
