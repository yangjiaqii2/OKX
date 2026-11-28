import { ArrowDown, ArrowUpRight, Check, ShieldCheck } from 'lucide-react';
import { motion, useReducedMotion } from 'motion/react';
import { useState } from 'react';
import { BrandMark } from '../components/BrandMark';
import { LanguageSwitch } from '../components/LanguageSwitch';
import { t, useLanguage } from '../i18n';
import { cn } from '../lib/cn';

type LandingPageProps = {
  onOpenAuth: (mode: 'login' | 'register') => void;
};

const TRACE_SCENARIOS = [
  {
    id: 'breakout',
    title: 'SOL-USDT-SWAP · 20M 结构突破与回踩确认',
    badge: 'TRADE · LONG',
    detail: 'V2 综合排序 84.6 · 价差 3.2 bps · 盘口深度 18,400 USDT · 止损距离 1.15% · 硬风控全部通过。',
  },
  {
    id: 'skip',
    title: 'ETH-USDT-SWAP · 高波动偏离均线，本轮观望',
    badge: 'SKIP · WATCH',
    detail: '5M 连续上影线占比超限且距 EMA20 过远，Agent 放弃追高并记录反事实观察样本。',
  },
  {
    id: 'protect',
    title: 'BTC-USDT-SWAP · 浮盈回撤保护触发移动止损',
    badge: 'PROTECT · EXIT',
    detail: '账本确认首笔止盈成交后，确定性保护引擎自动上移止损至保本位之上，锁定净收益。',
  },
] as const;

const FAQ_ITEMS = [
  {
    q: 'Quant Desk 是什么？它和普通“喊单机器人”有什么区别？',
    a: 'Quant Desk 是面向 OKX USDT 永续合约的个人交易 Agent 平台。全市场扫描、流动性过滤、杠杆与止损门禁、订单对账全部由确定性量化引擎执行；大模型只负责在合格候选中做结构化入场判断与事后复盘，绝不允许 AI 绕过风控直接盲目下单。',
  },
  {
    q: '我的 OKX API 密钥和资金安全吗？',
    a: '所有用户的 OKX API Key、Secret 与 Passphrase 均使用 AES-GCM 加密存储，接口永不回显明文，且数据严格按登录账号隔离。建议在 OKX 后台关闭提现权限并配置 IP 白名单，同时先在模拟盘或只读模式验证流程。',
  },
  {
    q: '为什么需要填写自己的大模型 API（BYOK）？',
    a: '平台支持任意 OpenAI 兼容接口（如 DeepSeek、OpenAI、Moonshot 等），你的个人交易 Agent 直接调用你自己配置的 API Key。这样既没有平台加价或额度排队，也能自由切换更适合自己风格的模型。',
  },
  {
    q: '注册后需要几步才能开始使用？',
    a: '只需三步：点击右上角完成注册登录 → 绑定你的 OKX 密钥 → 配置个人大模型 API 并测试连通性。随后即可查看全市场候选评分、模拟观察或通过风控门禁执行订单。',
  },
] as const;

export function LandingPage({ onOpenAuth }: LandingPageProps) {
  useLanguage();
  const reduce = useReducedMotion();
  const [activeScenario, setActiveScenario] = useState(1);

  return (
    <div className="min-h-dvh bg-[#FFFFFF] text-[#17211B] selection:bg-[#D6EE8A] selection:text-[#13261B]">
      {/* 1. Hero Section — Deep Forest Grainy Canvas */}
      <section className="relative flex min-h-dvh flex-col overflow-hidden bg-[#143528] text-white">
        <div
          className="pointer-events-none absolute inset-0"
          style={{
            background:
              'radial-gradient(62% 58% at 50% 42%, rgba(92, 148, 114, 0.52) 0%, rgba(28, 74, 54, 0.85) 52%, rgba(14, 34, 26, 0.98) 100%)',
          }}
        />
        <div className="grain-overlay pointer-events-none absolute inset-0 opacity-40 mix-blend-overlay" />

        {/* Minimal Transparent Navbar */}
        <header className="relative z-20 mx-auto flex w-full max-w-[1320px] items-center justify-between px-6 py-6 sm:px-10">
          <a href="#top" className="flex items-center gap-2.5 text-white no-underline">
            <BrandMark tone="light" size={30} />
            <span className="font-serif text-[19px] font-medium tracking-[-0.02em]">Quant Desk</span>
          </a>
          <div className="flex items-center gap-2.5 sm:gap-4">
            <a
              href="#workflow"
              className="hidden text-[14px] font-medium text-white/75 transition-colors hover:text-white sm:inline"
            >
              {t('工作流')}
            </a>
            <a
              href="#faq"
              className="hidden text-[14px] font-medium text-white/75 transition-colors hover:text-white sm:inline"
            >
              {t('常见问题')}
            </a>
            <div className="text-white/85 [&_button]:text-white/85 [&_button:hover]:bg-white/10">
              <LanguageSwitch />
            </div>
            <button
              type="button"
              onClick={() => onOpenAuth('login')}
              className="h-9 rounded-full px-3.5 text-[14px] font-medium text-white/85 transition-colors hover:bg-white/10 hover:text-white"
            >
              {t('登录')}
            </button>
            <button
              type="button"
              onClick={() => onOpenAuth('register')}
              className="h-9 rounded-full bg-white px-4 text-[14px] font-medium text-[#13261B] shadow-[0_2px_10px_rgba(0,0,0,0.16)] transition-transform hover:bg-[#F3F5F0] active:scale-[0.98]"
            >
              {t('免费开始')}
            </button>
          </div>
        </header>

        {/* Hero Centerpiece */}
        <div className="relative z-10 mx-auto flex max-w-[820px] flex-1 flex-col items-center justify-center px-6 pb-24 pt-12 text-center">
          <motion.div
            initial={reduce ? false : { opacity: 0, y: 14 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.55, ease: [0.22, 1, 0.36, 1] }}
            className="flex flex-col items-center"
          >
            <span className="mb-6 inline-flex items-center gap-2 rounded-full border border-white/15 bg-white/10 px-3.5 py-1 text-[12px] font-medium tracking-[0.02em] text-white/85 backdrop-blur-sm">
              <span className="size-1.5 rounded-full bg-[#D6EE8A]" />
              {t('OKX 永续合约 · 个人自动化交易 Agent 平台')}
            </span>
            <h1 className="font-serif text-[52px] font-normal leading-[1.08] tracking-[-0.025em] text-white sm:text-[74px]">
              {t('认识 Quant Desk')}
            </h1>
            <p className="mt-6 max-w-[560px] text-[16px] leading-[1.65] text-white/75 sm:text-[18px]">
              {t('为重视纪律的交易者而造。共享全市场量化扫描与硬风控底座，绑定你自己的 OKX 密钥与大模型 API，让交易决策清晰、可控、可复盘。')}
            </p>
            <div className="mt-9 flex flex-wrap items-center justify-center gap-3">
              <button
                type="button"
                onClick={() => onOpenAuth('register')}
                className="inline-flex h-12 items-center gap-2 rounded-full bg-[#D6EE8A] px-6 text-[15px] font-medium text-[#13261B] shadow-[0_6px_24px_rgba(0,0,0,0.2)] transition-all hover:brightness-105 active:scale-[0.98]"
              >
                {t('创建个人账号')}
                <ArrowUpRight size={16} />
              </button>
              <button
                type="button"
                onClick={() => onOpenAuth('login')}
                className="inline-flex h-12 items-center gap-2 rounded-full border border-white/20 bg-white/8 px-6 text-[15px] font-medium text-white backdrop-blur-sm transition-colors hover:bg-white/15"
              >
                {t('登录控制台')}
              </button>
            </div>
          </motion.div>

          {/* Bottom Frosted Capsule Cue (echoing reference pill bar) */}
          <a
            href="#philosophy"
            className="mt-16 inline-flex items-center gap-3 rounded-full border border-white/15 bg-[#0C1D15]/60 py-2 pl-5 pr-2 text-[13px] font-medium text-white/85 backdrop-blur-md transition-colors hover:bg-[#0C1D15]/80 sm:mt-20"
          >
            <span>{t('了解架构原则')} · {t('3 分钟上手')}</span>
            <span className="grid size-7 place-items-center rounded-full bg-white text-[#13261B]">
              <ArrowDown size={14} />
            </span>
          </a>
        </div>
      </section>

      {/* 2. Manifesto Band — Warm Painterly Atmospheric Gradient with Floating Micro-UI Cards */}
      <section
        id="philosophy"
        className="relative overflow-hidden py-28 sm:py-36 lg:py-44"
        style={{
          background:
            'linear-gradient(125deg, #A9B1A6 0%, #D9D5C9 36%, #ECE2D3 64%, #D9967F 100%)',
        }}
      >
        <div className="grain-overlay pointer-events-none absolute inset-0 opacity-25 mix-blend-multiply" />

        <div className="relative z-10 mx-auto max-w-[1220px] px-6">
          {/* Top Floating Cards (Desktop) */}
          <div className="mb-10 hidden items-start justify-between lg:flex">
            <div className="w-[268px] -rotate-1 rounded-[22px] bg-white/95 p-4 shadow-[0_12px_36px_rgba(19,38,27,0.08)] backdrop-blur">
              <div className="flex items-center gap-2 text-[12px] font-medium text-[#5A635D]">
                <BrandMark tone="forest" size={20} />
                <span>{t('信号已入围')}</span>
              </div>
              <p className="mt-2.5 text-[14px] font-medium leading-5 text-[#17211B]">
                SOL-USDT-SWAP · {t('20M 趋势结构确立，5M 回踩企稳')}
              </p>
              <span className="mt-3 inline-flex rounded-full bg-[#D6EE8A] px-2.5 py-0.5 text-[11px] font-semibold text-[#13261B]">
                V2 · 84.6
              </span>
            </div>

            <div className="w-[252px] rotate-1 rounded-[22px] bg-white/95 p-4 shadow-[0_12px_36px_rgba(19,38,27,0.08)] backdrop-blur">
              <div className="flex items-center gap-2 text-[12px] font-medium text-[#5A635D]">
                <BrandMark tone="forest" size={20} />
                <span>{t('多周期节奏')}</span>
              </div>
              <div className="mt-3 space-y-2 text-[12px]">
                <div className="flex items-center justify-between border-b border-black/5 pb-1.5">
                  <span className="font-semibold tracking-wider text-[#848C86]">20M</span>
                  <span className="text-[#17211B]">{t('主趋势与量价结构')}</span>
                </div>
                <div className="flex items-center justify-between">
                  <span className="font-semibold tracking-wider text-[#848C86]">5M</span>
                  <span className="text-[#17211B]">{t('精确入场与盘口校验')}</span>
                </div>
              </div>
            </div>
          </div>

          {/* Center Manifesto */}
          <div className="mx-auto max-w-[680px] text-center">
            <p className="text-[13px] font-medium tracking-[0.04em] text-[#3F4A43]">
              {t('我们的交易哲学')}
            </p>
            <h2 className="mt-4 font-serif text-[32px] font-normal leading-[1.32] tracking-[-0.02em] text-[#17211B] sm:text-[44px]">
              {t('交易不该是情绪与盯盘的消耗战。让量化引擎守住风险底线，让你的 Agent 冷静执行每一次判断。')}
            </h2>
          </div>

          {/* Bottom Floating Cards (Desktop) */}
          <div className="mt-12 hidden items-end justify-between lg:flex">
            <div className="inline-flex items-center gap-2.5 rounded-full bg-white/95 px-4 py-2.5 text-[13px] font-medium text-[#17211B] shadow-[0_10px_30px_rgba(19,38,27,0.08)]">
              <span className="grid size-5 place-items-center rounded-full bg-[#13261B] text-[#D6EE8A]">
                <Check size={12} strokeWidth={2.5} />
              </span>
              {t('硬风控门禁已通过 · 单笔止损 1.2%')}
            </div>

            <div className="flex w-[264px] items-center justify-between rounded-[22px] bg-white/95 p-4 shadow-[0_12px_36px_rgba(19,38,27,0.08)]">
              <div className="flex items-center gap-2.5">
                <span className="grid size-8 place-items-center rounded-full bg-[#13261B] text-[#D6EE8A]">
                  <ShieldCheck size={15} />
                </span>
                <div>
                  <div className="text-[12px] font-semibold text-[#17211B]">{t('执行与对账')}</div>
                  <div className="text-[11px] text-[#5A635D]">OKX REST · WS Sync</div>
                </div>
              </div>
              <div className="flex items-end gap-[3px]" aria-hidden="true">
                {[10, 18, 13, 22, 16, 12, 8].map((h, i) => (
                  <span
                    key={i}
                    className={cn('w-[3px] rounded-full', i < 4 ? 'bg-[#13261B]' : 'bg-[#D3D7D2]')}
                    style={{ height: h }}
                  />
                ))}
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* 3. Editorial 2-Column Scrollytelling Feature Section (Pure White + #F7F6F2 Squircles) */}
      <section id="workflow" className="bg-white py-28 sm:py-36">
        <div className="mx-auto max-w-[1160px] space-y-24 px-6 sm:space-y-32">
          {/* Feature Row 1 */}
          <div className="grid items-center gap-10 lg:grid-cols-[minmax(0,400px)_minmax(0,1fr)] lg:gap-20">
            <div>
              <h3 className="font-serif text-[34px] font-normal leading-[1.18] tracking-[-0.02em] text-[#17211B] sm:text-[42px]">
                {t('先有确定性量化，再有 AI 思考')}
              </h3>
              <p className="mt-4 text-[15px] leading-[1.7] text-[#5A635D]">
                {t('全市场永续合约先经过市值、流动性、盘口价差、资金费率与 V2 多因子评分过滤。只有真正满足硬指标的候选，才会进入 AI 的结构化评估视野。')}
              </p>
            </div>
            <div className="rounded-[28px] border border-[#17211B]/[0.06] bg-[#F7F6F2] p-6 sm:p-10">
              <div className="flex items-center gap-2 text-[13px] font-medium text-[#5A635D]">
                <BrandMark tone="forest" size={22} />
                <span>{t('候选准入与 V2 评分引擎')}</span>
              </div>
              <div className="mt-5 rounded-[22px] bg-white p-6 shadow-[0_6px_24px_rgba(23,33,27,0.04)]">
                <p className="text-[16px] leading-[1.6] text-[#17211B]">
                  {t('筛选条件：市值 ≥ 2,000 万 USD，盘口价差 ≤ 8 bps，较薄侧深度 ≥ 5,000 USDT，盈亏比 ≥ 1.5。')}
                </p>
                <div className="mt-5 flex flex-wrap gap-2">
                  <span className="rounded-full bg-[#D6EE8A] px-3 py-1 text-[12px] font-semibold text-[#13261B]">
                    {t('确定性风控优先')}
                  </span>
                  <span className="rounded-full bg-[#EFECE5] px-3 py-1 text-[12px] font-medium text-[#5A635D]">
                    {t('零幻觉数值校验')}
                  </span>
                  <span className="rounded-full bg-[#EFECE5] px-3 py-1 text-[12px] font-medium text-[#5A635D]">
                    {t('支持全链路复盘')}
                  </span>
                </div>
              </div>
            </div>
          </div>

          {/* Feature Row 2 — Signature Two-Bubble Comparison Card (White vs Pastel Lime) */}
          <div className="grid items-center gap-10 lg:grid-cols-[minmax(0,400px)_minmax(0,1fr)] lg:gap-20">
            <div>
              <h3 className="font-serif text-[34px] font-normal leading-[1.18] tracking-[-0.02em] text-[#17211B] sm:text-[42px]">
                {t('你的密钥，你的大模型，完全隔离')}
              </h3>
              <p className="mt-4 text-[15px] leading-[1.7] text-[#5A635D]">
                {t('开放注册后，每位用户独立绑定自己的 OKX API 密钥与 OpenAI 兼容大模型 API（BYOK）。所有密钥经 AES-GCM 加密存储，账本、持仓与额度彼此彻底隔离。')}
              </p>
            </div>
            <div className="overflow-hidden rounded-[28px] border border-[#17211B]/[0.06] bg-[#F7F6F2] p-6 sm:p-10">
              <div className="flex items-center gap-2 text-[13px] font-medium text-[#5A635D]">
                <BrandMark tone="forest" size={22} />
                <span>{t('双密钥独立隔离架构')}</span>
              </div>
              <div className="mt-5 grid gap-4 sm:grid-cols-2">
                <div className="flex min-h-[176px] flex-col justify-between rounded-[22px] bg-white p-5 shadow-[0_6px_24px_rgba(23,33,27,0.04)]">
                  <p className="text-[14px] leading-6 text-[#3F4A43]">
                    {t('绑定你自己的 OKX 账户，支持 DEMO 模拟盘与 LIVE 实盘，Secret 与 Passphrase 永不回显。')}
                  </p>
                  <span className="text-[12px] font-medium text-[#848C86]">
                    OKX Vault · AES-GCM
                  </span>
                </div>
                <div className="flex min-h-[176px] flex-col justify-between rounded-[22px] border border-[#13261B] bg-[#D6EE8A] p-5">
                  <p className="text-[14px] leading-6 text-[#13261B]">
                    {t('接入你自己的 DeepSeek、OpenAI 或 Moonshot API，自主选择模型与策略风格，费用完全透明。')}
                  </p>
                  <span className="text-[12px] font-semibold text-[#13261B]">
                    LLM API · BYOK
                  </span>
                </div>
              </div>
            </div>
          </div>

          {/* Feature Row 3 */}
          <div className="grid items-center gap-10 lg:grid-cols-[minmax(0,400px)_minmax(0,1fr)] lg:gap-20">
            <div>
              <h3 className="font-serif text-[34px] font-normal leading-[1.18] tracking-[-0.02em] text-[#17211B] sm:text-[42px]">
                {t('从只读观察，到纪律化自动执行')}
              </h3>
              <p className="mt-4 text-[15px] leading-[1.7] text-[#5A635D]">
                {t('不必一开始就交出实盘权限。你可以先观察选币与模拟表现，再启用人工确认下单，确认策略贴合预期后再逐步放开自动化执行。')}
              </p>
            </div>
            <div className="rounded-[28px] border border-[#17211B]/[0.06] bg-[#F7F6F2] p-6 sm:p-10">
              <div className="grid gap-3 sm:grid-cols-3">
                {[
                  { tag: '01 · DISABLED / PAPER', title: t('只读与模拟观察'), desc: t('零资金风险验证选币与决策质量') },
                  { tag: '02 · MANUAL', title: t('人工复核确认'), desc: t('核对保证金、杠杆与止盈止损后下单') },
                  { tag: '03 · AUTO', title: t('硬风控自动执行'), desc: t('预算预留、幂等下单与盈利保护闭环') },
                ].map((stage, idx) => (
                  <div
                    key={stage.tag}
                    className={cn(
                      'rounded-[20px] p-4',
                      idx === 2
                        ? 'bg-[#13261B] text-white'
                        : 'bg-white text-[#17211B] shadow-[0_4px_18px_rgba(23,33,27,0.04)]',
                    )}
                  >
                    <div className={cn('text-[11px] font-semibold tracking-wider', idx === 2 ? 'text-[#D6EE8A]' : 'text-[#848C86]')}>
                      {stage.tag}
                    </div>
                    <div className="mt-2 text-[14px] font-semibold">{stage.title}</div>
                    <p className={cn('mt-1.5 text-[12px] leading-5', idx === 2 ? 'text-white/70' : 'text-[#5A635D]')}>
                      {stage.desc}
                    </p>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* 4. Interactive Scenario Pill Stack (Echoing "Conversations with real humans") */}
      <section className="bg-white py-24 sm:py-32">
        <div className="mx-auto max-w-[760px] px-6 text-center">
          <h2 className="font-serif text-[34px] font-normal leading-[1.2] tracking-[-0.02em] text-[#17211B] sm:text-[44px]">
            {t('看得见的每一次判断')}
          </h2>
          <p className="mt-3 text-[15px] text-[#5A635D]">
            {t('点击下方场景，预览 Agent 从信号筛选到风控落单的透明轨迹。')}
          </p>

          <div className="mt-10 flex flex-col items-center gap-3">
            {TRACE_SCENARIOS.map((item, idx) => {
              const isActive = idx === activeScenario;
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => setActiveScenario(idx)}
                  className={cn(
                    'flex w-full items-center justify-between rounded-full border px-6 text-left transition-all duration-200',
                    isActive
                      ? 'max-w-[580px] border-[#17211B]/10 bg-white py-4 text-[#17211B] shadow-[0_14px_40px_rgba(19,38,27,0.09)]'
                      : 'max-w-[490px] border-[#17211B]/[0.06] bg-[#FAFAF8] py-3 text-[#959C97] hover:text-[#5A635D]',
                  )}
                >
                  <span className={cn('truncate pr-4', isActive ? 'text-[15px] font-medium' : 'text-[14px]')}>
                    {t(item.title)}
                  </span>
                  {isActive ? (
                    <span className="grid size-9 shrink-0 place-items-center rounded-full bg-[#13261B] text-[#D6EE8A]">
                      <ArrowUpRight size={16} />
                    </span>
                  ) : null}
                </button>
              );
            })}
          </div>

          <div className="mx-auto mt-6 max-w-[560px] rounded-[22px] bg-[#F7F6F2] px-6 py-4 text-left">
            <span className="inline-flex rounded-full bg-[#D6EE8A] px-2.5 py-0.5 text-[11px] font-semibold text-[#13261B]">
              {TRACE_SCENARIOS[activeScenario].badge}
            </span>
            <p className="mt-2 text-[13px] leading-6 text-[#3F4A43]">
              {t(TRACE_SCENARIOS[activeScenario].detail)}
            </p>
          </div>
        </div>
      </section>

      {/* 5. Highlighted Editorial Pull Quote */}
      <section className="bg-white py-24 sm:py-32">
        <div className="mx-auto max-w-[680px] px-6 text-center">
          <p className="text-[12px] font-medium tracking-[0.06em] text-[#848C86]">Quant Desk</p>
          <blockquote className="mt-5 font-serif text-[30px] font-normal leading-[1.42] tracking-[-0.02em] text-[#848C86] sm:text-[40px]">
            <span className="rounded-[6px] bg-[#D6EE8A] px-2 py-0.5 text-[#13261B]">
              {t('行情瞬息万变。')}
            </span>{' '}
            <span className="text-[#17211B]">
              {t('我们依然相信，纪律、风控边界与透明复盘，比盲目高频下单更重要。')}
            </span>
          </blockquote>
        </div>
      </section>

      {/* 6. Open Editorial FAQ Section */}
      <section id="faq" className="bg-white py-24 sm:py-32">
        <div className="mx-auto max-w-[640px] px-6">
          <h2 className="font-serif text-[34px] font-normal leading-[1.2] tracking-[-0.02em] text-[#17211B] sm:text-[42px]">
            {t('常见问题')}
          </h2>
          <div className="mt-10 divide-y divide-[#17211B]/[0.08]">
            {FAQ_ITEMS.map((item) => (
              <div key={item.q} className="py-7">
                <h3 className="text-[16px] font-semibold leading-6 text-[#17211B]">{t(item.q)}</h3>
                <p className="mt-2.5 text-[14px] leading-[1.7] text-[#5A635D]">{t(item.a)}</p>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* 7. Dark Grainy Pre-Footer CTA & Minimal Footer */}
      <footer className="relative overflow-hidden bg-[#070B08] text-white">
        <div
          className="pointer-events-none absolute inset-0"
          style={{
            background:
              'linear-gradient(135deg, rgba(168, 194, 174, 0.16) 0%, rgba(16, 36, 26, 0.35) 45%, rgba(6, 10, 7, 0.98) 100%)',
          }}
        />
        <div className="grain-overlay pointer-events-none absolute inset-0 opacity-40 mix-blend-overlay" />

        <div className="relative z-10 mx-auto max-w-[760px] px-6 pb-20 pt-28 text-center sm:pt-36">
          <h2 className="font-serif text-[38px] font-normal leading-[1.15] tracking-[-0.02em] text-white sm:text-[54px]">
            {t('开启你的纪律化交易 Agent')}
          </h2>
          <p className="mx-auto mt-4 max-w-[460px] text-[15px] leading-7 text-white/65">
            {t('注册账号，绑定你自己的 OKX 密钥与大模型 API，在统一交易台中掌控每一次入场与风控。')}
          </p>

          <div className="mx-auto mt-9 flex max-w-[440px] flex-col items-center gap-3 sm:flex-row sm:rounded-full sm:border sm:border-white/15 sm:bg-white/[0.06] sm:p-1.5 sm:backdrop-blur-md">
            <button
              type="button"
              onClick={() => onOpenAuth('login')}
              className="h-11 w-full rounded-full border border-white/15 px-5 text-[14px] font-medium text-white/85 transition-colors hover:bg-white/10 sm:w-auto sm:flex-1 sm:border-0"
            >
              {t('已有账号？登录')}
            </button>
            <button
              type="button"
              onClick={() => onOpenAuth('register')}
              className="h-11 w-full rounded-full bg-[#D6EE8A] px-6 text-[14px] font-semibold text-[#13261B] transition-all hover:brightness-105 active:scale-[0.98] sm:w-auto"
            >
              {t('立即注册体验')}
            </button>
          </div>
        </div>

        <div className="relative z-10 mx-auto flex max-w-[1320px] flex-col items-center justify-between gap-2 border-t border-white/10 px-6 py-6 text-[12px] text-white/45 sm:flex-row sm:px-10">
          <span>Disciplined intelligence.</span>
          <span>© 2026 Quant Desk</span>
        </div>
      </footer>
    </div>
  );
}
