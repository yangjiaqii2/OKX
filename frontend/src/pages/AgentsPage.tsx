import { BookOpen, Bot, Check, Clock, Pause, Pin, Play, Plus, ShieldCheck, Sliders, Sparkles, Terminal, Trash2, X, Zap } from 'lucide-react';
import { FormEvent, useEffect, useState } from 'react';
import { useNotify } from 'react-admin';
import { Link } from 'react-router-dom';
import { quantApi } from '../api/quantApi';
import { BrandMark } from '../components/BrandMark';
import { PageHeader, PageShell } from '../components/PageShell';
import { PanelState } from '../components/PanelState';
import { Alert } from '../components/ui/alert';
import { Button } from '../components/ui/button';
import { ConfirmDialog } from '../components/ui/confirm-dialog';
import { Field, Input } from '../components/ui/field';
import { t, useLanguage } from '../i18n';
import { cn } from '../lib/cn';

type AgentTemplate = {
  code: string;
  title: string;
  description: string;
  defaultBudgetUsdt: number;
  defaultMaxOpenPositions: number;
  defaultMaxLeverage: number;
  defaultMinTotalScore: number;
  defaultMinFinalRankScore: number;
  defaultMaxSingleLossPct: number;
  defaultMaxDailyLossPct: number;
  defaultDirection: 'BOTH' | 'LONG_ONLY' | 'SHORT_ONLY';
  defaultSystemPrompt: string;
};

type TradingAgent = {
  id: number;
  name: string;
  templateCode: string;
  status: 'PAUSED' | 'RUNNING' | 'ERROR_STOPPED';
  runMode: 'PAPER' | 'MANUAL_CONFIRM' | 'AUTO';
  llmProfileId?: number | null;
  allocatedBudgetUsdt: number;
  maxOpenPositions: number;
  maxLeverage: number;
  minTotalScore: number;
  minFinalRankScore: number;
  maxSingleLossPct: number;
  maxDailyLossPct: number;
  directionPreference: 'BOTH' | 'LONG_ONLY' | 'SHORT_ONLY';
  symbolWhitelist: string;
  symbolBlacklist: string;
  systemPrompt: string;
  toolCallingEnabled?: boolean;
  evalIntervalMinutes?: number;
  versionNo: number;
  lastDecision?: string | null;
  lastSummary?: string | null;
  lastErrorCode?: string | null;
  lastRunAt?: string | null;
};

type AgentRunStep = {
  stepNo: number;
  stage: string;
  status: string;
  detail: string;
  elapsedMs: number;
};

type AgentRun = {
  id: number;
  agentId: number;
  agentVersionNo: number;
  scanRunId?: string | null;
  runMode: string;
  decision: string;
  selectedInstId?: string | null;
  selectedDirection?: string | null;
  llmModel?: string | null;
  summaryReason?: string | null;
  steps: AgentRunStep[];
  elapsedMs: number;
  createdAt: string;
};

type LlmProfileOption = {
  id: number;
  name: string;
  modelName: string;
  active: boolean;
};

type AgentMemory = {
  id: number;
  agentId: number;
  memoryType: string;
  instId?: string | null;
  content: string;
  pinned: boolean;
  enabled: boolean;
  updatedAt: string;
};

type AgentFormState = {
  id: number | null;
  name: string;
  templateCode: string;
  runMode: 'PAPER' | 'MANUAL_CONFIRM' | 'AUTO';
  llmProfileId: string;
  allocatedBudgetUsdt: string;
  maxOpenPositions: string;
  maxLeverage: string;
  minTotalScore: string;
  minFinalRankScore: string;
  maxSingleLossPct: string;
  maxDailyLossPct: string;
  directionPreference: 'BOTH' | 'LONG_ONLY' | 'SHORT_ONLY';
  symbolWhitelist: string;
  symbolBlacklist: string;
  systemPrompt: string;
  toolCallingEnabled: boolean;
  evalIntervalMinutes: number;
};

const PROMPT_DISCIPLINE_CHIPS = [
  '拒绝长上影线追高，宁可观望',
  '优先等待 5M 回踩均线企稳后再入场',
  '仅在高流动性、盘口价差 ≤ 5bps 时出手',
  '震荡市降低出手频率，只做高胜率突破',
  '资金费率极端拥挤时自动跳过，避免反向挤压',
  '若同一标的近期刚止损，本轮优先观望',
];

function defaultFormFromTemplate(template?: AgentTemplate): AgentFormState {
  return {
    id: null,
    name: template ? template.title : '稳健趋势 Agent',
    templateCode: template ? template.code : 'TREND_BALANCED',
    runMode: 'PAPER',
    llmProfileId: '',
    allocatedBudgetUsdt: String(template?.defaultBudgetUsdt ?? 100),
    maxOpenPositions: String(template?.defaultMaxOpenPositions ?? 2),
    maxLeverage: String(template?.defaultMaxLeverage ?? 5),
    minTotalScore: String(template?.defaultMinTotalScore ?? 65),
    minFinalRankScore: String(template?.defaultMinFinalRankScore ?? 65),
    maxSingleLossPct: String(template?.defaultMaxSingleLossPct ?? 1.2),
    maxDailyLossPct: String(template?.defaultMaxDailyLossPct ?? 3.5),
    directionPreference: template?.defaultDirection ?? 'BOTH',
    symbolWhitelist: '',
    symbolBlacklist: '',
    systemPrompt: template?.defaultSystemPrompt ?? '',
    toolCallingEnabled: true,
    evalIntervalMinutes: 5,
  };
}

export function AgentsPage() {
  useLanguage();
  const notify = useNotify();
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [runningId, setRunningId] = useState<number | null>(null);
  const [templates, setTemplates] = useState<AgentTemplate[]>([]);
  const [agents, setAgents] = useState<TradingAgent[]>([]);
  const [llmProfiles, setLlmProfiles] = useState<LlmProfileOption[]>([]);
  const [wsStatus, setWsStatus] = useState<{ state?: string; reason?: string }>({});
  const [editorOpen, setEditorOpen] = useState(false);
  const [form, setForm] = useState<AgentFormState>(() => defaultFormFromTemplate());
  const [selectedTraceAgent, setSelectedTraceAgent] = useState<TradingAgent | null>(null);
  const [runs, setRuns] = useState<AgentRun[]>([]);
  const [loadingRuns, setLoadingRuns] = useState(false);
  const [selectedMemoryAgent, setSelectedMemoryAgent] = useState<TradingAgent | null>(null);
  const [memories, setMemories] = useState<AgentMemory[]>([]);
  const [loadingMemories, setLoadingMemories] = useState(false);
  const [reflecting, setReflecting] = useState(false);
  const [newMemory, setNewMemory] = useState({ memoryType: 'USER_RULE', instId: '', content: '', pinned: true });
  const [deleteTarget, setDeleteTarget] = useState<TradingAgent | null>(null);

  async function loadAll() {
    setLoading(true);
    try {
      const [tpls, list, profiles, ws] = await Promise.all([
        quantApi.agentTemplates() as Promise<AgentTemplate[]>,
        quantApi.agents() as Promise<TradingAgent[]>,
        quantApi.llmProfiles() as Promise<LlmProfileOption[]>,
        quantApi.agentWsStatus().catch(() => ({})) as Promise<{ state?: string; reason?: string }>,
      ]);
      setTemplates(Array.isArray(tpls) ? tpls : []);
      setAgents(Array.isArray(list) ? list : []);
      setLlmProfiles(Array.isArray(profiles) ? profiles : []);
      setWsStatus(ws ?? {});
    } catch (error) {
      notify(error instanceof Error ? error.message : t('加载交易 Agent 失败'), { type: 'error' });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadAll();
  }, []);

  async function loadRuns(agent: TradingAgent) {
    setSelectedTraceAgent(agent);
    setLoadingRuns(true);
    try {
      const list = (await quantApi.agentRuns(agent.id, 20)) as AgentRun[];
      setRuns(Array.isArray(list) ? list : []);
    } catch (error) {
      notify(error instanceof Error ? error.message : t('加载决策轨迹失败'), { type: 'error' });
    } finally {
      setLoadingRuns(false);
    }
  }

  async function loadMemories(agent: TradingAgent) {
    setSelectedMemoryAgent(agent);
    setLoadingMemories(true);
    try {
      const list = (await quantApi.agentMemories(agent.id)) as AgentMemory[];
      setMemories(Array.isArray(list) ? list : []);
    } catch (error) {
      notify(error instanceof Error ? error.message : t('加载 Agent 记忆失败'), { type: 'error' });
    } finally {
      setLoadingMemories(false);
    }
  }

  async function addMemory(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedMemoryAgent || !newMemory.content.trim()) return;
    setSubmitting(true);
    try {
      await quantApi.addAgentMemory(selectedMemoryAgent.id, {
        memoryType: newMemory.memoryType,
        instId: newMemory.instId.trim() || undefined,
        content: newMemory.content.trim(),
        pinned: newMemory.pinned,
      });
      setNewMemory({ memoryType: 'USER_RULE', instId: '', content: '', pinned: true });
      notify(t('记忆条目已保存'), { type: 'success' });
      await loadMemories(selectedMemoryAgent);
    } catch (error) {
      notify(error instanceof Error ? error.message : t('保存记忆失败'), { type: 'error' });
    } finally {
      setSubmitting(false);
    }
  }

  async function triggerReflect() {
    if (!selectedMemoryAgent || reflecting) return;
    setReflecting(true);
    try {
      const added = (await quantApi.reflectAgent(selectedMemoryAgent.id)) as AgentMemory[];
      notify(`${t('AI 复盘完成，新提炼记忆：')}${Array.isArray(added) ? added.length : 0}`, { type: 'success' });
      await loadMemories(selectedMemoryAgent);
    } catch (error) {
      notify(error instanceof Error ? error.message : t('AI 复盘反思失败'), { type: 'error' });
    } finally {
      setReflecting(false);
    }
  }

  async function togglePinMemory(memoryId: number) {
    if (!selectedMemoryAgent) return;
    try {
      await quantApi.togglePinAgentMemory(selectedMemoryAgent.id, memoryId);
      await loadMemories(selectedMemoryAgent);
    } catch (error) {
      notify(error instanceof Error ? error.message : t('操作失败'), { type: 'error' });
    }
  }

  async function deleteMemory(memoryId: number) {
    if (!selectedMemoryAgent) return;
    try {
      await quantApi.deleteAgentMemory(selectedMemoryAgent.id, memoryId);
      await loadMemories(selectedMemoryAgent);
    } catch (error) {
      notify(error instanceof Error ? error.message : t('删除失败'), { type: 'error' });
    }
  }

  function openCreate() {
    setForm(defaultFormFromTemplate(templates[0]));
    setEditorOpen(true);
  }

  function openEdit(agent: TradingAgent) {
    setForm({
      id: agent.id,
      name: agent.name,
      templateCode: agent.templateCode,
      runMode: agent.runMode,
      llmProfileId: agent.llmProfileId ? String(agent.llmProfileId) : '',
      allocatedBudgetUsdt: String(agent.allocatedBudgetUsdt),
      maxOpenPositions: String(agent.maxOpenPositions),
      maxLeverage: String(agent.maxLeverage),
      minTotalScore: String(agent.minTotalScore),
      minFinalRankScore: String(agent.minFinalRankScore),
      maxSingleLossPct: String(agent.maxSingleLossPct),
      maxDailyLossPct: String(agent.maxDailyLossPct),
      directionPreference: agent.directionPreference,
      symbolWhitelist: agent.symbolWhitelist ?? '',
      symbolBlacklist: agent.symbolBlacklist ?? '',
      systemPrompt: agent.systemPrompt ?? '',
      toolCallingEnabled: agent.toolCallingEnabled !== false,
      evalIntervalMinutes: agent.evalIntervalMinutes ?? 5,
    });
    setEditorOpen(true);
  }

  function applyTemplate(template: AgentTemplate) {
    setForm((current) => ({
      ...defaultFormFromTemplate(template),
      id: current.id,
      llmProfileId: current.llmProfileId,
      runMode: current.runMode,
    }));
  }

  async function submitForm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    try {
      await quantApi.saveAgent({
        id: form.id,
        name: form.name.trim(),
        templateCode: form.templateCode,
        runMode: form.runMode,
        llmProfileId: form.llmProfileId ? Number(form.llmProfileId) : null,
        allocatedBudgetUsdt: Number(form.allocatedBudgetUsdt),
        maxOpenPositions: Number(form.maxOpenPositions),
        maxLeverage: Number(form.maxLeverage),
        minTotalScore: Number(form.minTotalScore),
        minFinalRankScore: Number(form.minFinalRankScore),
        maxSingleLossPct: Number(form.maxSingleLossPct),
        maxDailyLossPct: Number(form.maxDailyLossPct),
        directionPreference: form.directionPreference,
        symbolWhitelist: form.symbolWhitelist,
        symbolBlacklist: form.symbolBlacklist,
        systemPrompt: form.systemPrompt,
        toolCallingEnabled: form.toolCallingEnabled,
        evalIntervalMinutes: form.evalIntervalMinutes,
      });
      notify(form.id ? t('交易 Agent 配置已更新') : t('交易 Agent 已创建'), { type: 'success' });
      setEditorOpen(false);
      await loadAll();
    } catch (error) {
      notify(error instanceof Error ? error.message : t('保存交易 Agent 失败'), { type: 'error' });
    } finally {
      setSubmitting(false);
    }
  }

  async function toggleStatus(agent: TradingAgent) {
    setSubmitting(true);
    try {
      if (agent.status === 'RUNNING') {
        await quantApi.pauseAgent(agent.id);
        notify(t('Agent 已暂停'), { type: 'info' });
      } else {
        await quantApi.startAgent(agent.id);
        notify(t('Agent 已启动，将在每轮共享扫盘后自动评估'), { type: 'success' });
      }
      await loadAll();
    } catch (error) {
      notify(error instanceof Error ? error.message : t('切换状态失败'), { type: 'error' });
    } finally {
      setSubmitting(false);
    }
  }

  async function runNow(agent: TradingAgent) {
    setRunningId(agent.id);
    try {
      const run = (await quantApi.runAgentOnce(agent.id)) as AgentRun;
      notify(`${agent.name} · ${run.decision}`, { type: run.decision === 'ERROR' || run.decision === 'BLOCKED' ? 'warning' : 'success' });
      await loadAll();
      await loadRuns(agent);
    } catch (error) {
      notify(error instanceof Error ? error.message : t('试跑失败'), { type: 'error' });
    } finally {
      setRunningId(null);
    }
  }

  async function confirmDelete(agent: TradingAgent) {
    setSubmitting(true);
    try {
      await quantApi.deleteAgent(agent.id);
      notify(t('交易 Agent 已删除'), { type: 'success' });
      if (selectedTraceAgent?.id === agent.id) {
        setSelectedTraceAgent(null);
        setRuns([]);
      }
      await loadAll();
    } catch (error) {
      notify(error instanceof Error ? error.message : t('删除失败'), { type: 'error' });
    } finally {
      setSubmitting(false);
      setDeleteTarget(null);
    }
  }

  if (loading) {
    return (
      <PageShell title={t('我的 Agent')}>
        <PanelState loading />
      </PageShell>
    );
  }

  return (
    <PageShell title={t('我的 Agent')}>
      <PageHeader
        title={t('我的交易 Agent')}
        subtitle={t('共享全市场量化扫描与硬风控底座；每个 Agent 使用你自己的大模型 API 与独立策略边界，每轮思考与拦截原因全程可追溯。')}
        onRefresh={() => void loadAll()}
        actions={
          <div className="flex items-center gap-2">
            {wsStatus.state ? (
              <span className="inline-flex items-center gap-1.5 rounded-full border border-line-strong bg-raised px-3 py-1 text-[12px] text-muted">
                <span className={cn('size-1.5 rounded-full', wsStatus.state === 'READY' ? 'bg-up' : 'bg-faint')} />
                {t('私有 WS')}: {wsStatus.state}
              </span>
            ) : null}
            <Button variant="primary" size="sm" onClick={openCreate}>
              <Plus size={14} />
              {t('新建 Agent')}
            </Button>
          </div>
        }
      />

      {llmProfiles.length === 0 ? (
        <div className="mt-4">
          <Alert severity="warning">
            {t('尚未配置个人大模型 API（BYOK）。请先前往')}{' '}
            <Link to="/llm-settings" className="font-semibold underline">
              {t('大模型 API 设置')}
            </Link>{' '}
            {t('添加并启用你的 API Key，随后即可启动或试跑 Agent。')}
          </Alert>
        </div>
      ) : null}

      {/* Agent Cards Grid */}
      <div className="mt-6 grid gap-5 lg:grid-cols-2">
        {agents.length === 0 ? (
          <div className="col-span-full rounded-[24px] border border-line bg-raised p-10 text-center">
            <div className="mx-auto grid size-12 place-items-center rounded-full bg-accent-soft text-accent-ink">
              <Bot size={22} />
            </div>
            <h2 className="mt-4 font-serif text-[24px] font-medium text-ink">{t('还没有创建个人交易 Agent')}</h2>
            <p className="mx-auto mt-2 max-w-[460px] text-[14px] leading-6 text-muted">
              {t('从内置策略模板一键创建，默认以 PAPER 模拟模式无风险运行，看清每轮决策轨迹后再切入确认下单。')}
            </p>
            <Button className="mt-5" variant="primary" size="md" onClick={openCreate}>
              <Sparkles size={15} />
              {t('创建第一个交易 Agent')}
            </Button>
          </div>
        ) : (
          agents.map((agent) => (
            <div
              key={agent.id}
              className="flex flex-col justify-between rounded-[24px] border border-line bg-raised p-6 shadow-[0_6px_24px_rgba(19,38,27,0.04)]"
            >
              <div>
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <h3 className="truncate font-serif text-[22px] font-medium leading-7 text-ink">
                        {agent.name}
                      </h3>
                      <span className="rounded-full bg-sunken px-2 py-0.5 text-[11px] font-medium text-muted">
                        v{agent.versionNo}
                      </span>
                    </div>
                    <div className="mt-1.5 flex flex-wrap items-center gap-2">
                      <StatusBadge status={agent.status} />
                      <span className="rounded-full border border-line-strong px-2.5 py-0.5 text-[11px] font-semibold text-ink">
                        {agent.runMode === 'PAPER'
                          ? t('PAPER · 模拟观察')
                          : agent.runMode === 'MANUAL_CONFIRM'
                            ? t('MANUAL · 手动确认')
                            : t('AUTO · 自动执行')}
                      </span>
                      <span className="text-[12px] text-muted">{agent.templateCode}</span>
                    </div>
                  </div>
                </div>

                <div className="mt-5 grid grid-cols-2 gap-3 rounded-[18px] bg-canvas p-3.5 text-[12px] sm:grid-cols-4">
                  <Metric label={t('分配预算')} value={`${agent.allocatedBudgetUsdt} USDT`} />
                  <Metric label={t('杠杆 / 持仓')} value={`≤${agent.maxLeverage}x · ${agent.maxOpenPositions}`} />
                  <Metric label={t('止损 / 日亏')} value={`${agent.maxSingleLossPct}% / ${agent.maxDailyLossPct}%`} />
                  <Metric label={t('评分门槛')} value={`≥${agent.minTotalScore} / ${agent.minFinalRankScore}`} />
                </div>

                {agent.lastSummary ? (
                  <div className="mt-4 rounded-[16px] border border-line bg-canvas/60 px-3.5 py-2.5 text-[12px] leading-5 text-muted">
                    <span className="mr-2 font-semibold text-ink">{agent.lastDecision || '-'}</span>
                    {agent.lastSummary}
                  </div>
                ) : null}
              </div>

              <div className="mt-5 flex flex-wrap items-center gap-2 border-t border-line pt-4">
                <Button
                  variant={agent.status === 'RUNNING' ? 'ghost' : 'primary'}
                  size="sm"
                  disabled={submitting}
                  onClick={() => void toggleStatus(agent)}
                >
                  {agent.status === 'RUNNING' ? <Pause size={13} /> : <Play size={13} />}
                  {agent.status === 'RUNNING' ? t('暂停') : t('启动')}
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={runningId === agent.id}
                  onClick={() => void runNow(agent)}
                >
                  <Zap size={13} />
                  {runningId === agent.id ? t('运行中...') : t('立即试跑')}
                </Button>
                <Button variant="ghost" size="sm" onClick={() => void loadRuns(agent)}>
                  <Terminal size={13} />
                  {t('决策轨迹')}
                </Button>
                <Button variant="ghost" size="sm" onClick={() => void loadMemories(agent)}>
                  <BookOpen size={13} />
                  {t('记忆与复盘')}
                </Button>
                <Button variant="quiet" size="sm" onClick={() => openEdit(agent)}>
                  {t('编辑')}
                </Button>
                <Button
                  variant="quiet"
                  size="icon"
                  className="ml-auto text-muted hover:text-down"
                  aria-label={t('删除')}
                  onClick={() => setDeleteTarget(agent)}
                >
                  <Trash2 size={14} />
                </Button>
              </div>
            </div>
          ))
        )}
      </div>

      {/* Agent Memory & Reflection Panel */}
      {selectedMemoryAgent ? (
        <div className="mt-8 rounded-[24px] border border-line bg-raised p-6">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h2 className="font-serif text-[22px] font-medium text-ink">
                {selectedMemoryAgent.name} · {t('长期记忆与复盘反思')}
              </h2>
              <p className="mt-1 text-[13px] text-muted">
                {t('启用的记忆会自动注入该 Agent 每轮决策上下文；可手动添加规则或点击「AI 复盘提炼」总结近期教训。')}
              </p>
            </div>
            <div className="flex gap-2">
              <Button variant="primary" size="sm" disabled={reflecting} onClick={() => void triggerReflect()}>
                <Sparkles size={13} />
                {reflecting ? t('复盘提炼中...') : t('AI 复盘提炼记忆')}
              </Button>
              <Button variant="ghost" size="sm" onClick={() => setSelectedMemoryAgent(null)}>
                {t('收起')}
              </Button>
            </div>
          </div>

          <form className="mt-5 grid gap-3 rounded-[18px] bg-canvas p-4 sm:grid-cols-[140px_140px_minmax(0,1fr)_auto]" onSubmit={(e) => void addMemory(e)}>
            <select
              className="h-10 rounded-[10px] border border-line-strong bg-field px-2.5 text-[13px] text-ink"
              value={newMemory.memoryType}
              onChange={(e) => setNewMemory((c) => ({ ...c, memoryType: e.target.value }))}
            >
              <option value="USER_RULE">{t('用户规则')}</option>
              <option value="LESSON">{t('复盘教训')}</option>
              <option value="SYMBOL_NOTE">{t('币种笔记')}</option>
              <option value="MARKET_REGIME">{t('行情状态')}</option>
            </select>
            <Input
              className="h-10"
              placeholder={t('合约(可选)')}
              value={newMemory.instId}
              onChange={(e) => setNewMemory((c) => ({ ...c, instId: e.target.value }))}
            />
            <Input
              className="h-10"
              required
              placeholder={t('例如：连续长上影线突破时避免追高，优先等待 5M 回踩')}
              value={newMemory.content}
              onChange={(e) => setNewMemory((c) => ({ ...c, content: e.target.value }))}
            />
            <Button variant="primary" size="sm" type="submit" disabled={submitting} className="h-10">
              {t('添加记忆')}
            </Button>
          </form>

          {loadingMemories ? (
            <PanelState loading className="mt-5" />
          ) : memories.length === 0 ? (
            <p className="mt-5 text-[13px] text-muted">{t('暂无记忆条目。可手动添加交易纪律，或在运行几轮后点击「AI 复盘提炼记忆」。')}</p>
          ) : (
            <div className="mt-4 space-y-2.5">
              {memories.map((m) => (
                <div key={m.id} className="flex items-start justify-between gap-3 rounded-[16px] border border-line bg-canvas px-4 py-3">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2 text-[11px] font-semibold">
                      <span className={cn('rounded-full px-2 py-0.5', m.pinned ? 'bg-[#D6EE8A] text-[#13261B]' : 'bg-sunken text-muted')}>
                        {m.memoryType}
                      </span>
                      {m.instId ? <span className="text-ink">{m.instId}</span> : null}
                      {m.pinned ? <span className="text-accent-ink">{t('置顶')}</span> : null}
                    </div>
                    <p className="mt-1.5 text-[13px] leading-5 text-ink">{m.content}</p>
                  </div>
                  <div className="flex shrink-0 items-center gap-1">
                    <Button variant="quiet" size="icon" aria-label={t('置顶')} onClick={() => void togglePinMemory(m.id)}>
                      <Pin size={13} className={m.pinned ? 'text-accent-ink' : 'text-muted'} />
                    </Button>
                    <Button variant="quiet" size="icon" aria-label={t('删除')} onClick={() => void deleteMemory(m.id)}>
                      <Trash2 size={13} className="text-muted hover:text-down" />
                    </Button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      ) : null}

      {/* Decision Trace Panel */}
      {selectedTraceAgent ? (
        <div className="mt-8 rounded-[24px] border border-line bg-raised p-6">
          <div className="flex items-center justify-between gap-3">
            <div>
              <h2 className="font-serif text-[22px] font-medium text-ink">
                {selectedTraceAgent.name} · {t('决策追踪时间线')}
              </h2>
              <p className="mt-1 text-[13px] text-muted">
                {t('完整展示每轮「过滤 → 模型判断 → 硬风控 → 执行」四步链路与耗时。')}
              </p>
            </div>
            <Button variant="ghost" size="sm" onClick={() => setSelectedTraceAgent(null)}>
              {t('收起')}
            </Button>
          </div>

          {loadingRuns ? (
            <PanelState loading className="mt-6" />
          ) : runs.length === 0 ? (
            <p className="mt-6 text-[13px] text-muted">{t('暂无运行记录。点击卡片上的「立即试跑」可马上生成一条完整决策轨迹。')}</p>
          ) : (
            <div className="mt-5 space-y-4">
              {runs.map((run) => (
                <div key={run.id} className="rounded-[20px] border border-line bg-canvas p-4">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <DecisionBadge decision={run.decision} />
                      {run.selectedInstId ? (
                        <span className="text-[13px] font-semibold text-ink">
                          {run.selectedInstId} {run.selectedDirection ?? ''}
                        </span>
                      ) : null}
                      <span className="text-[12px] text-muted">
                        v{run.agentVersionNo} · {run.runMode} · {run.llmModel || t('未调用模型')} · {run.elapsedMs}ms
                      </span>
                    </div>
                    <span className="text-[12px] text-faint">{new Date(run.createdAt).toLocaleString()}</span>
                  </div>
                  {run.summaryReason ? (
                    <p className="mt-2 text-[13px] leading-5 text-ink/90">{run.summaryReason}</p>
                  ) : null}
                  {run.steps && run.steps.length > 0 ? (
                    <div className="mt-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
                      {run.steps.map((step) => (
                        <div key={`${run.id}-${step.stepNo}`} className="rounded-[14px] border border-line bg-raised p-3">
                          <div className="flex items-center justify-between text-[11px] font-semibold">
                            <span className="text-ink">{step.stepNo}. {step.stage}</span>
                            <span className={cn(
                              step.status === 'PASSED' ? 'text-up' : step.status === 'SKIPPED' ? 'text-muted' : 'text-down',
                            )}>
                              {step.status} · {step.elapsedMs}ms
                            </span>
                          </div>
                          <p className="mt-1.5 text-[12px] leading-5 text-muted">{step.detail}</p>
                        </div>
                      ))}
                    </div>
                  ) : null}
                </div>
              ))}
            </div>
          )}
        </div>
      ) : null}

      {/* High-Craft Editorial Agent Creation / Edit Studio */}
      {editorOpen ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-[#0B140F]/60 p-3 backdrop-blur-sm sm:p-6">
          <form
            onSubmit={(event) => void submitForm(event)}
            className="flex max-h-[92dvh] w-full max-w-[1040px] flex-col overflow-hidden rounded-[30px] border border-line bg-canvas shadow-[0_32px_90px_rgba(8,18,12,0.34)]"
          >
            {/* Studio Header */}
            <div className="flex items-center justify-between border-b border-line bg-raised px-6 py-4 sm:px-8">
              <div className="flex items-center gap-3">
                <BrandMark tone="forest" size={28} />
                <div>
                  <h2 className="font-serif text-[24px] font-normal leading-7 tracking-[-0.02em] text-ink">
                    {form.id ? t('编辑交易 Agent') : t('打造你的交易 Agent')}
                  </h2>
                  <p className="text-[12px] text-muted">
                    {t('确定性量化风控打底，绑定个人大模型 API 独立推理。')}
                  </p>
                </div>
              </div>
              <button
                type="button"
                aria-label={t('关闭')}
                className="grid size-9 place-items-center rounded-full border border-line-strong bg-canvas text-muted transition-colors hover:bg-sunken hover:text-ink"
                onClick={() => setEditorOpen(false)}
              >
                <X size={16} />
              </button>
            </div>

            {/* Studio Body: 2-Column Editorial Layout */}
            <div className="grid flex-1 overflow-y-auto lg:grid-cols-[minmax(0,1fr)_330px]">
              {/* Left Scrollable Configuration Canvas */}
              <div className="space-y-7 p-6 sm:p-8">
                {/* 01 Strategy Archetype */}
                {templates.length > 0 ? (
                  <section>
                    <div className="mb-3 flex items-center justify-between">
                      <span className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">
                        01 · {t('选择策略原型')}
                      </span>
                      <span className="text-[12px] text-faint">{t('点击卡片一键填充参数')}</span>
                    </div>
                    <div className="grid gap-3 sm:grid-cols-2">
                      {templates.map((tpl) => {
                        const selected = form.templateCode === tpl.code;
                        return (
                          <button
                            key={tpl.code}
                            type="button"
                            onClick={() => applyTemplate(tpl)}
                            className={cn(
                              'group relative rounded-[22px] border p-4 text-left transition-all',
                              selected
                                ? 'border-[#16382B] bg-raised shadow-[0_10px_28px_rgba(19,38,27,0.08)] dark:border-[#D6EE8A]'
                                : 'border-line bg-raised/70 hover:border-line-strong hover:bg-raised',
                            )}
                          >
                            <div className="flex items-start justify-between gap-2">
                              <div className="text-[14px] font-semibold text-ink">{tpl.title}</div>
                              <span
                                className={cn(
                                  'grid size-5 shrink-0 place-items-center rounded-full transition-colors',
                                  selected ? 'bg-[#13261B] text-[#D6EE8A] dark:bg-[#D6EE8A] dark:text-[#13261B]' : 'border border-line-strong',
                                )}
                              >
                                {selected ? <Check size={11} strokeWidth={2.8} /> : null}
                              </span>
                            </div>
                            <p className="mt-1.5 text-[12px] leading-5 text-muted">{tpl.description}</p>
                            <div className="mt-3 flex flex-wrap gap-1.5 text-[11px]">
                              <span className={cn('rounded-full px-2 py-0.5 font-semibold', selected ? 'bg-[#D6EE8A] text-[#13261B]' : 'bg-sunken text-muted')}>
                                ≤{tpl.defaultMaxLeverage}x
                              </span>
                              <span className="rounded-full bg-sunken px-2 py-0.5 text-muted">
                                SL {tpl.defaultMaxSingleLossPct}%
                              </span>
                              <span className="rounded-full bg-sunken px-2 py-0.5 text-muted">
                                {t('评分')} ≥{tpl.defaultMinTotalScore}
                              </span>
                            </div>
                          </button>
                        );
                      })}
                    </div>
                  </section>
                ) : null}

                {/* 02 Identity, Run Mode, Cadence & Model */}
                <section className="rounded-[24px] border border-line bg-raised p-5 sm:p-6">
                  <div className="mb-4 text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">
                    02 · {t('身份、运行模式与评估节奏')}
                  </div>

                  <Field label={t('Agent 名称')}>
                    <Input
                      required
                      placeholder={t('例如：SOL 趋势先锋')}
                      value={form.name}
                      onChange={(event) => setForm((c) => ({ ...c, name: event.target.value }))}
                    />
                  </Field>

                  <div className="mt-5">
                    <div className="mb-2 text-[12px] font-medium text-muted">{t('运行模式')}</div>
                    <div className="grid gap-2 sm:grid-cols-3">
                      {([
                        ['PAPER', t('PAPER · 模拟'), t('零资金风险验证')],
                        ['MANUAL_CONFIRM', t('MANUAL · 审批'), t('生成待确认订单')],
                        ['AUTO', t('AUTO · 自动'), t('DEMO / LIVE 下单')],
                      ] as const).map(([val, title, desc]) => (
                        <button
                          key={val}
                          type="button"
                          onClick={() => setForm((c) => ({ ...c, runMode: val }))}
                          className={cn(
                            'rounded-[16px] border px-3.5 py-2.5 text-left transition-all',
                            form.runMode === val
                              ? 'border-[#16382B] bg-[#16382B] text-white dark:border-[#D6EE8A] dark:bg-[#D6EE8A] dark:text-[#13261B]'
                              : 'border-line bg-canvas text-ink hover:border-line-strong',
                          )}
                        >
                          <div className="text-[13px] font-semibold">{title}</div>
                          <div className={cn('mt-0.5 text-[11px]', form.runMode === val ? 'opacity-80' : 'text-muted')}>{desc}</div>
                        </button>
                      ))}
                    </div>
                  </div>

                  <div className="mt-5 grid gap-5 sm:grid-cols-2">
                    <div>
                      <div className="mb-2 flex items-center justify-between text-[12px] font-medium text-muted">
                        <span className="inline-flex items-center gap-1"><Clock size={12} /> {t('评估冷却间隔')}</span>
                        <span className="rounded-full bg-[#D6EE8A] px-2 py-0.5 text-[10px] font-semibold text-[#13261B]">
                          {t('省 80%+ 额度')}
                        </span>
                      </div>
                      <div className="grid grid-cols-4 gap-1.5 rounded-full bg-sunken p-1">
                        {[5, 15, 30, 60].map((mins) => (
                          <button
                            key={mins}
                            type="button"
                            onClick={() => setForm((c) => ({ ...c, evalIntervalMinutes: mins }))}
                            className={cn(
                              'h-8 rounded-full text-[12px] font-semibold transition-colors',
                              form.evalIntervalMinutes === mins
                                ? 'bg-raised text-ink shadow-[0_1px_3px_rgba(19,38,27,0.1)]'
                                : 'text-muted hover:text-ink',
                            )}
                          >
                            {mins}m
                          </button>
                        ))}
                      </div>
                    </div>

                    <div>
                      <div className="mb-2 text-[12px] font-medium text-muted">{t('方向偏好')}</div>
                      <div className="grid grid-cols-3 gap-1.5 rounded-full bg-sunken p-1">
                        {([
                          ['BOTH', t('双向')],
                          ['LONG_ONLY', t('仅做多')],
                          ['SHORT_ONLY', t('仅做空')],
                        ] as const).map(([val, label]) => (
                          <button
                            key={val}
                            type="button"
                            onClick={() => setForm((c) => ({ ...c, directionPreference: val }))}
                            className={cn(
                              'h-8 rounded-full text-[12px] font-semibold transition-colors',
                              form.directionPreference === val
                                ? 'bg-raised text-ink shadow-[0_1px_3px_rgba(19,38,27,0.1)]'
                                : 'text-muted hover:text-ink',
                            )}
                          >
                            {label}
                          </button>
                        ))}
                      </div>
                    </div>
                  </div>

                  <div className="mt-5">
                    <div className="mb-2 text-[12px] font-medium text-muted">{t('绑定大模型 API（BYOK）')}</div>
                    <div className="flex flex-wrap gap-2">
                      <button
                        type="button"
                        onClick={() => setForm((c) => ({ ...c, llmProfileId: '' }))}
                        className={cn(
                          'rounded-full border px-3.5 py-1.5 text-[12px] font-medium transition-colors',
                          form.llmProfileId === ''
                            ? 'border-accent bg-accent-soft text-accent-ink'
                            : 'border-line-strong bg-canvas text-muted hover:text-ink',
                        )}
                      >
                        {t('跟随默认启用模型')}
                      </button>
                      {llmProfiles.map((p) => (
                        <button
                          key={p.id}
                          type="button"
                          onClick={() => setForm((c) => ({ ...c, llmProfileId: String(p.id) }))}
                          className={cn(
                            'rounded-full border px-3.5 py-1.5 text-[12px] font-medium transition-colors',
                            form.llmProfileId === String(p.id)
                              ? 'border-accent bg-accent-soft text-accent-ink'
                              : 'border-line-strong bg-canvas text-muted hover:text-ink',
                          )}
                        >
                          {p.name} · {p.modelName}
                        </button>
                      ))}
                    </div>
                  </div>
                </section>

                {/* 03 Capital & Hard Risk Guardrails */}
                <section className="rounded-[24px] border border-line bg-raised p-5 sm:p-6">
                  <div className="mb-4 flex items-center justify-between">
                    <span className="inline-flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">
                      <Sliders size={12} /> 03 · {t('资金与硬风控边界')}
                    </span>
                    <span className="text-[11px] text-faint">{t('受平台安全上限保护，AI 无法绕过')}</span>
                  </div>
                  <div className="grid grid-cols-2 gap-3.5 sm:grid-cols-3">
                    <Field label={t('分配预算 (USDT)')}>
                      <Input type="number" min="10" step="10" required value={form.allocatedBudgetUsdt} onChange={(e) => setForm((c) => ({ ...c, allocatedBudgetUsdt: e.target.value }))} />
                    </Field>
                    <Field label={t('杠杆上限 (≤10x)')}>
                      <Input type="number" min="1" max="10" required value={form.maxLeverage} onChange={(e) => setForm((c) => ({ ...c, maxLeverage: e.target.value }))} />
                    </Field>
                    <Field label={t('最大持仓 (≤3)')}>
                      <Input type="number" min="1" max="3" required value={form.maxOpenPositions} onChange={(e) => setForm((c) => ({ ...c, maxOpenPositions: e.target.value }))} />
                    </Field>
                    <Field label={t('单笔止损 % (≤2%)')}>
                      <Input type="number" min="0.2" max="2" step="0.1" required value={form.maxSingleLossPct} onChange={(e) => setForm((c) => ({ ...c, maxSingleLossPct: e.target.value }))} />
                    </Field>
                    <Field label={t('综合分门槛')}>
                      <Input type="number" min="40" max="95" required value={form.minTotalScore} onChange={(e) => setForm((c) => ({ ...c, minTotalScore: e.target.value }))} />
                    </Field>
                    <Field label={t('最终排名分门槛')}>
                      <Input type="number" min="40" max="95" required value={form.minFinalRankScore} onChange={(e) => setForm((c) => ({ ...c, minFinalRankScore: e.target.value }))} />
                    </Field>
                  </div>
                </section>

                {/* 04 Symbols & Strategy Prompt */}
                <section className="rounded-[24px] border border-line bg-raised p-5 sm:p-6">
                  <div className="mb-4 text-[11px] font-semibold uppercase tracking-[0.08em] text-muted">
                    04 · {t('标的偏好与交易风格 Prompt')}
                  </div>
                  <div className="grid gap-4 sm:grid-cols-2">
                    <Field label={t('币种白名单（留空扫描全市场）')}>
                      <Input placeholder="BTC, ETH, SOL" value={form.symbolWhitelist} onChange={(e) => setForm((c) => ({ ...c, symbolWhitelist: e.target.value }))} />
                    </Field>
                    <Field label={t('币种黑名单（可选）')}>
                      <Input placeholder="DOGE, PEPE" value={form.symbolBlacklist} onChange={(e) => setForm((c) => ({ ...c, symbolBlacklist: e.target.value }))} />
                    </Field>
                  </div>

                  <div className="mt-4">
                    <div className="mb-2 text-[12px] font-medium text-muted">{t('快捷纪律标签（点击追加到 Prompt）')}</div>
                    <div className="flex flex-wrap gap-1.5">
                      {PROMPT_DISCIPLINE_CHIPS.map((chip) => (
                        <button
                          key={chip}
                          type="button"
                          onClick={() => setForm((c) => ({
                            ...c,
                            systemPrompt: c.systemPrompt.includes(chip)
                              ? c.systemPrompt
                              : `${c.systemPrompt.trim()}${c.systemPrompt.trim() ? '\n- ' : '- '}${chip}`,
                          }))}
                          className="rounded-full border border-line-strong bg-canvas px-3 py-1 text-[11px] text-muted transition-colors hover:border-accent hover:text-ink"
                        >
                          + {t(chip)}
                        </button>
                      ))}
                    </div>
                  </div>

                  <Field className="mt-4" label={t('交易风格 Prompt')}>
                    <textarea
                      rows={8}
                      className="w-full rounded-[16px] border border-line-strong bg-canvas p-3.5 text-[13px] leading-6 text-ink focus:outline focus:outline-2 focus:outline-accent"
                      value={form.systemPrompt}
                      onChange={(event) => setForm((c) => ({ ...c, systemPrompt: event.target.value }))}
                    />
                  </Field>

                  <div className="mt-4 flex items-center justify-between gap-4 rounded-[16px] bg-canvas px-4 py-3">
                    <div>
                      <div className="text-[13px] font-semibold text-ink">{t('受控只读工具查证')}</div>
                      <div className="text-[11px] text-muted">{t('决策前最多 2 步查盘口深度与历史记录，严禁暴露下单工具')}</div>
                    </div>
                    <button
                      type="button"
                      role="switch"
                      aria-checked={form.toolCallingEnabled}
                      onClick={() => setForm((c) => ({ ...c, toolCallingEnabled: !c.toolCallingEnabled }))}
                      className={cn(
                        'relative inline-flex h-6 w-11 shrink-0 items-center rounded-full transition-colors',
                        form.toolCallingEnabled ? 'bg-[#16382B] dark:bg-[#D6EE8A]' : 'bg-line-strong',
                      )}
                    >
                      <span
                        className={cn(
                          'inline-block size-4 rounded-full bg-white transition-transform dark:bg-[#102016]',
                          form.toolCallingEnabled ? 'translate-x-6' : 'translate-x-1',
                        )}
                      />
                    </button>
                  </div>
                </section>
              </div>

              {/* Right Sticky Atmospheric Preview Card */}
              <div className="relative flex flex-col justify-between overflow-hidden border-t border-line bg-[#143528] p-6 text-white lg:border-l lg:border-t-0">
                <div
                  className="pointer-events-none absolute inset-0"
                  style={{
                    background:
                      'radial-gradient(80% 70% at 80% 15%, rgba(92, 148, 114, 0.45) 0%, rgba(20, 53, 40, 0.92) 58%, rgba(10, 26, 19, 0.99) 100%)',
                  }}
                />
                <div className="grain-overlay pointer-events-none absolute inset-0 opacity-35 mix-blend-overlay" />

                <div className="relative z-10">
                  <div className="flex items-center justify-between">
                    <span className="inline-flex items-center gap-1.5 rounded-full bg-[#D6EE8A] px-2.5 py-0.5 text-[11px] font-semibold text-[#13261B]">
                      <ShieldCheck size={12} />
                      {form.runMode}
                    </span>
                    <span className="text-[11px] text-white/60">{form.templateCode}</span>
                  </div>

                  <h3 className="mt-4 font-serif text-[26px] font-normal leading-8 text-white">
                    {form.name.trim() || t('未命名 Agent')}
                  </h3>
                  <p className="mt-1 text-[12px] text-white/65">
                    {form.symbolWhitelist.trim()
                      ? `${t('专注标的')} · ${form.symbolWhitelist.toUpperCase()}`
                      : t('全市场共享扫盘 · 精选 Top 3')}
                  </p>

                  <div className="mt-6 space-y-3 rounded-[20px] border border-white/12 bg-white/[0.07] p-4 text-[12px] backdrop-blur-sm">
                    <div className="flex justify-between">
                      <span className="text-white/60">{t('分配预算')}</span>
                      <span className="font-semibold text-white">{form.allocatedBudgetUsdt || 0} USDT</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-white/60">{t('杠杆 / 持仓上限')}</span>
                      <span className="font-semibold text-white">≤{form.maxLeverage}x · {form.maxOpenPositions}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-white/60">{t('单笔止损 / 评分')}</span>
                      <span className="font-semibold text-white">{form.maxSingleLossPct}% · ≥{form.minTotalScore}</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-white/60">{t('评估冷却间隔')}</span>
                      <span className="font-semibold text-[#D6EE8A]">{form.evalIntervalMinutes}m</span>
                    </div>
                    <div className="flex justify-between">
                      <span className="text-white/60">{t('预估日均 Token')}</span>
                      <span className="font-semibold text-white">
                        {form.symbolWhitelist.trim()
                          ? '~0.02–0.06M'
                          : form.evalIntervalMinutes >= 30
                            ? '~0.02–0.05M'
                            : form.evalIntervalMinutes >= 15
                              ? '~0.04–0.09M'
                              : '~0.10–0.25M'}
                      </span>
                    </div>
                  </div>
                </div>

                <div className="relative z-10 mt-6 space-y-2.5">
                  <button
                    type="submit"
                    disabled={submitting}
                    className="h-11 w-full rounded-full bg-[#D6EE8A] text-[14px] font-semibold text-[#13261B] shadow-[0_6px_20px_rgba(0,0,0,0.22)] transition-all hover:brightness-105 disabled:opacity-50"
                  >
                    {submitting ? t('提交中') : form.id ? t('保存新版本') : t('创建交易 Agent')}
                  </button>
                  <button
                    type="button"
                    onClick={() => setEditorOpen(false)}
                    className="h-10 w-full rounded-full border border-white/15 bg-white/5 text-[13px] font-medium text-white/80 transition-colors hover:bg-white/12"
                  >
                    {t('取消')}
                  </button>
                </div>
              </div>
            </div>
          </form>
        </div>
      ) : null}

      <ConfirmDialog
        open={Boolean(deleteTarget)}
        title={t('删除交易 Agent')}
        content={deleteTarget ? `${t('确认删除 Agent「')}${deleteTarget.name}${t('」？历史订单不受影响，但该 Agent 配置将被移除。')}` : ''}
        confirmLabel={t('删除')}
        cancelLabel={t('返回')}
        tone="warning"
        pending={submitting}
        onConfirm={() => { if (deleteTarget) void confirmDelete(deleteTarget); }}
        onClose={() => setDeleteTarget(null)}
      />
    </PageShell>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-[11px] text-muted">{label}</div>
      <div className="mt-0.5 font-semibold text-ink">{value}</div>
    </div>
  );
}

function StatusBadge({ status }: { status: TradingAgent['status'] }) {
  if (status === 'RUNNING') {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-full bg-[#D6EE8A] px-2.5 py-0.5 text-[11px] font-semibold text-[#13261B]">
        <span className="size-1.5 rounded-full bg-[#13261B]" />
        {t('运行中')}
      </span>
    );
  }
  if (status === 'ERROR_STOPPED') {
    return (
      <span className="inline-flex items-center rounded-full bg-down-soft px-2.5 py-0.5 text-[11px] font-semibold text-down">
        {t('异常停止')}
      </span>
    );
  }
  return (
    <span className="inline-flex items-center rounded-full bg-sunken px-2.5 py-0.5 text-[11px] font-medium text-muted">
      {t('已暂停')}
    </span>
  );
}

function DecisionBadge({ decision }: { decision: string }) {
  const cls =
    decision === 'PAPER_TRADE'
      ? 'bg-[#D6EE8A] text-[#13261B]'
      : decision === 'MANUAL_ORDER_CREATED' || decision === 'AUTO_ORDER_EXECUTED'
        ? 'bg-[#16382B] text-[#F7FAF4]'
        : decision === 'SKIP'
          ? 'bg-sunken text-muted'
          : 'bg-down-soft text-down';
  return (
    <span className={cn('inline-flex rounded-full px-2.5 py-0.5 text-[11px] font-semibold', cls)}>
      {decision}
    </span>
  );
}
