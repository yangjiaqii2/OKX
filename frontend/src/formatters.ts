import { colors } from './designTokens';
import type { ReactNode } from 'react';
import { createElement } from 'react';
import { getLanguage, t } from './i18n';

function translatedLabel(labels: Record<string, string>, key: string): string | undefined {
  return Object.prototype.hasOwnProperty.call(labels, key) ? t(labels[key]) : undefined;
}

export function formatBoolean(value: unknown) {
  return value === true || value === 'true' ? t("是") : t("否");
}

export function formatTrend(value: unknown) {
  const text = String(value ?? '');
  const map: Record<string, string> = {
    BULLISH: t("多头趋势"),
    BEARISH: t("空头趋势"),
    NEUTRAL: t("震荡观望"),
    UNKNOWN: t("未知"),
  };
  return translatedLabel(map, text) ?? (text || '-');
}

export function formatAction(value: unknown) {
  const text = String(value ?? '');
  const map: Record<string, string> = {
    OPEN_LONG: t("开多"),
    OPEN_SHORT: t("开空"),
    CLOSE_LONG: t("平多"),
    CLOSE_SHORT: t("平空"),
  };
  return translatedLabel(map, text) ?? (text || '-');
}

/** 复盘列表「开仓方向」：只显示「多」或「空」。 */
export function formatOpenDirection(
  action: unknown,
  posSide: unknown,
) {
  const actionText = String(action ?? '').toUpperCase();
  if (actionText.includes('LONG')) return t("多");
  if (actionText.includes('SHORT')) return t("空");

  const sideText = String(posSide ?? '').toLowerCase();
  if (sideText === 'long' || sideText === 'buy') return t("多");
  if (sideText === 'short' || sideText === 'sell') return t("空");

  return '-';
}

export function formatSignalType(value: unknown) {
  const text = String(value ?? '');
  const map: Record<string, string> = {
    STRONG_LONG: t("20m 强势偏多"),
    WAIT_RECLAIM: t("冲高回落，等待确认"),
    PULLBACK_LONG: t("回调做多"),
    WAIT_OVERHEATED: t("过热等待"),
    REVERSAL_SHORT: t("反转做空"),
    TREND_SHORT: t("趋势做空"),
    WAIT_OVERSOLD: t("超卖等待"),
    NEUTRAL: t("中性观望"),
    NO_TRADE: t("不交易"),
  };
  return translatedLabel(map, text) ?? formatAutoTradeEnum(text || '-');
}

export function formatScanAction(action: unknown, reason: unknown) {
  if (action) return formatSignalType(action);
  const recordedAction = String(reason ?? '').match(/\bcandidate_action_not_auto_trade_allowed_([A-Z_]+)\b/);
  return recordedAction ? formatSignalType(recordedAction[1]) : t("未记录");
}

export function formatDecisionSource(value: unknown) {
  const text = String(value ?? '');
  const map: Record<string, string> = {
    AI_PRIMARY: t("AI主选"),
    AI_WAIT: t("AI 本轮观望"),
    RULE_FALLBACK: t("规则保底"),
    NO_ELIGIBLE_CANDIDATE: t("没有满足条件的候选合约"),
  };
  return translatedLabel(map, text) ?? formatAutoTradeEnum(text || '-');
}

export function formatEntryMode(value: unknown) {
  const text = String(value ?? '');
  const map: Record<string, string> = {
    MARKET: t("市价"),
    LIMIT: t("限价"),
    WAIT: t("等待"),
    RULE: t("规则"),
    NO_ENTRY: t("不入场"),
  };
  return translatedLabel(map, text) ?? formatAutoTradeEnum(text || '-');
}

/** 复盘列表「阶段」：后端 stage 码转中文。 */
export function formatStage(value: unknown) {
  const text = String(value ?? '');
  const map: Record<string, string> = {
    SCAN: t("扫描"),
    PLAN: t("生成方案"),
    SUBMIT: t("提交"),
    ENTRY: t("入场"),
    WAIT_ENTRY: t("等待入场"),
    CONFIRM: t("确认"),
    OKX_SUBMIT: t("交易所提交"),
    AUTO_TRADE: t("自动交易"),
    UNKNOWN: t("未知"),
  };
  return translatedLabel(map, text) ?? formatAutoTradeEnum(text || '-');
}

export function formatSide(value: unknown) {
  const text = String(value ?? '');
  const map: Record<string, string> = {
    buy: t("买入"),
    sell: t("卖出"),
    long: t("多仓"),
    short: t("空仓"),
    cross: t("全仓"),
    isolated: t("逐仓"),
    LIMIT: t("限价"),
    MARKET: t("市价"),
    limit: t("限价"),
    market: t("市价"),
  };
  return translatedLabel(map, text) ?? (text || '-');
}

export function formatStatus(value: unknown) {
  const text = String(value ?? '');
  const map: Record<string, string> = {
    true: t("通过"),
    false: t("拦截"),
    PENDING_CONFIRM: t("待确认"),
    CONFIRMED: t("已确认"),
    SUBMITTED: t("入场委托已提交"),
    ENTRY_SUBMITTED: t("入场委托已提交"),
    ENTRY_FILLED: t("入场已成交"),
    ENTRY_PARTIAL_FILLED: t("入场部分成交"),
    ENTRY_PENDING: t("等待成交"),
    WAITING_ENTRY: t("等待入场条件"),
    UNKNOWN_SUBMIT_STATUS: t("提交未知"),
    ENTRY_TIMEOUT_CANCELLED: t("入场超时已撤"),
    PROTECTION_SUBMITTED: t("保护单已提交"),
    PROTECTION_FAILED: t("保护单失败"),
    SIDEWAYS_TIMEOUT: t("横盘超时"),
    SIDEWAYS_TIMEOUT_TP_ADJUSTED: t("横盘TP已调整"),
    MAX_HOLD_TIMEOUT: t("最大持仓超时"),
    CLOSE_SUBMITTING: t("平仓提交中"),
    CLOSE_SUBMIT_UNKNOWN: t("平仓结果未知"),
    CLOSE_SUBMITTED: t("平仓已提交"),
    CLOSED: t("已关闭"),
    EMERGENCY_ATTENTION_REQUIRED: t("紧急处理"),
    EXECUTED: t("已执行"),
    REJECTED: t("已拒绝"),
    FAILED: t("失败"),
    SKIPPED: t("已跳过"),
    CANCELLED: t("已取消"),
    EXPIRED: t("已过期"),
    LOW: t("低风险"),
    MEDIUM: t("中风险"),
    HIGH: t("高风险"),
    NOT_EVALUATED: t("待订单预检"),
    BLOCKED: t("已拦截"),
    OKX_REAL: t("OKX实盘"),
    OKX_DEMO: t("OKX模拟盘"),
    OKX_ERROR: t("OKX异常"),
    OKX_UNBOUND: t("未绑定"),
    OKX_EMPTY: t("账户为空"),
    MANAGED: t("系统管理"),
    UNMANAGED: t("外部/未托管"),
    NOT_RECONCILED: t("待对账"),
  };
  return translatedLabel(map, text) ?? (text || '-');
}

const autoTradeReasonLabels: Record<string, string> = {
  no_candidate_passed_auto_gate: '没有候选通过自动交易门槛',
  no_order_submitted: '没有提交订单',
  CANONICAL_PLAN_REJECTED: '订单确认时交易计划被拒绝',
  EXECUTABLE_RISK_EXCEEDS_RISK_BUDGET: '按交易所价格和数量计算的止损金额超过风险预算',
  NET_RISK_REWARD_AFTER_COSTS_TOO_LOW: '扣除交易成本后盈亏比不足',
  PLAN_NOT_EXECUTABLE: '方案未通过',
  QUALITY_AND_RISK_BELOW_THRESHOLD: '入场质量和风险评估均未达标',
  QUALITY_BELOW_THRESHOLD: '入场质量低于门槛',
  RISK_BELOW_THRESHOLD: '风险可接受概率低于门槛',
  MODEL_WAIT: '模型选择观望',
  ENTRY_MODE_WAIT: '模型选择的入场方式需要确认',
  HARD_DENY: '命中硬性准入规则',
  DIRECTION_CONFLICT: '模型方向与规则方向冲突',
  AI_CALL_FAILED: 'AI 调用失败',
  AI_RESPONSE_INVALID: 'AI 回答无法解析或校验',
  NET_RISK_OR_MARGIN_BELOW_MINIMUM_SIZE: '扣除成本后的风险预算或保证金不足以达到最小下单张数',
  NET_STOP_LOSS_EXCEEDS_RISK_BUDGET: '预计止损亏损超过单笔风险预算',
  MARKET_EXECUTION_BOOK_STALE: '实时盘口已过期',
  MARKET_EXECUTION_BOOK_UNAVAILABLE: '实时盘口不可用',
  MARKET_EXECUTION_PRICE_UNAVAILABLE: '实时可成交报价不可用',
  MARKET_PRICE_DEVIATION_TOO_WIDE: '可成交报价偏离计划价格过大',
  MARKET_ENTRY_OUTSIDE_EXIT_RANGE: '当前价格已超出方案止盈止损区间',
  EXECUTION_RULES_OR_FEES_UNAVAILABLE: '合约规则或交易费率不可用',
  backtest_gate_required_but_not_available: '需要回测通过，但当前没有可用回测结果',
  market_environment_high_risk: '市场环境风险较高',
  news_risk_critical: '重大新闻风险，禁止交易',
  news_risk_high: '高新闻风险，禁止交易',
  signal_type_not_tradable_NO_TRADE: '信号类型不可交易：不交易',
  stop_loss_pct_above_5: '止损距离超过5%',
  'risk_reward_below_1.5': '盈亏比低于1.5',
  account_available_balance_unavailable: '可用余额不可用或为零',
  AUTO_TRADE_EXECUTION_ALREADY_RUNNING: '上一轮自动交易仍在执行',
  PLAN_RISK_MARGIN_UNAVAILABLE: '交易计划的风险保证金不可用',
  auto_trade_total_budget_not_configured: '未配置自动交易总预算',
  duplicate_or_missing_closed_candle: '重复扫描或缺少已闭合K线',
  auto_trade_disabled_by_config: '配置已关闭自动交易',
  auto_trade_runtime_switch_off: '运行时开关已关闭自动交易',
  NO_ELIGIBLE_CANDIDATE: '没有满足条件的候选合约',
  AI_WAITING_ENTRY: 'AI判断等待入场条件',
  AI_WAIT_SKIPPED: 'AI 判断本轮暂不入场',
  ai_primary_wait: 'AI主选结果为等待',
  WAITING_ENTRY_RULE_INELIGIBLE: '等待入场条件已不再满足规则准入',
  total_budget_exhausted: '自动交易总预算已用完',
  budget_system_error: '自动交易预算系统异常',
  budget_reserve_failed_due_to_concurrency: '预算预留发生并发冲突',
  budget_order_margin_below_min: '订单保证金低于最低要求',
  pre_confirm_refresh_failed: '下单前行情刷新失败',
  okx_submit_status_unknown: 'OKX订单提交结果未知',
  local_active_entry_order: '已有本地入场订单：',
  SYMBOL_ALREADY_HELD: '已有该合约持仓',
  SYMBOL_ENTRY_LOCKED: '该合约入场请求已锁定',
  short_term_reclaim_unconfirmed: '5m 回落结构尚未收复',
  SYMBOL_ALREADY_IN_FLIGHT: '该合约已有持仓或入场请求',
  symbol_already_in_flight: '该合约已有持仓或入场请求',
  okx_current_active_order: 'OKX已有该合约的活动订单',
  okx_current_orders_unavailable: 'OKX当前订单同步不可用',
  position_snapshot_unavailable: '持仓快照不可用',
  position_capacity_full: '持仓容量已满',
  position_target_satisfied: '已达到目标持仓数量',
  exposure_guard_passed: '相关性敞口检查通过',
  FIVE_MINUTE_REVERSE: '5分钟周期出现反向信号',
  BTC_ETH_REVERSE: 'BTC/ETH市场方向与候选相反',
  LIVE_REFRESH_CANDIDATE_INVALID: '实时刷新后的候选无效',
  LIVE_REFRESH_TICKER_EMPTY: '实时刷新未获取到行情',
  LIVE_REFRESH_PRICE_INVALID: '实时刷新价格无效',
  LIVE_REFRESH_FUNDING_UNAVAILABLE: '实时刷新资金费率不可用',
  LIVE_REFRESH_CANDLE_TIMESTAMP_UNAVAILABLE: '实时刷新K线时间不可用',
  LIVE_REFRESH_ORIGINAL_CANDLE_TIMESTAMP_UNAVAILABLE: '原始分析K线时间不可用',
  pre_confirm_refresh_disabled: '下单前刷新已关闭',
  pre_confirm_refresh_passed: '下单前刷新通过',
};

const autoTradeEnumLabels: Record<string, string> = {
  AUTO_TRADE_ALLOWED: '允许自动交易',
  WAIT: '等待',
  WAIT_CONFIRM: '等待确认',
  WAIT_OVERHEATED: '过热等待',
  WAIT_OVERSOLD: '超卖等待',
  WAIT_PULLBACK: '等待回调',
  NO_TRADE: '不交易',
  WATCH_ONLY: '仅观察',
  READY: '准备交易',
  RISK_OFF: '风险规避',
  HIGH_VOLATILITY: '高波动',
  BULLISH: '多头',
  BEARISH: '空头',
  CHOPPY: '震荡',
  UNKNOWN: '未知',
  STRONG_LONG: '20m 强势偏多',
  WAIT_RECLAIM: '冲高回落，等待确认',
  PULLBACK_LONG: '回调做多',
  REVERSAL_SHORT: '反转做空',
  TREND_SHORT: '趋势做空',
  NEUTRAL: '中性',
  AI_PRIMARY: 'AI主选',
  AI_WAIT: 'AI 本轮观望',
  RULE_FALLBACK: '规则保底',
  NO_ELIGIBLE_CANDIDATE: '没有满足条件的候选合约',
  MARKET: '市价',
  LIMIT: '限价',
  RULE: '规则',
  AUTO_CANDIDATE_SKIPPED: '候选已跳过',
  PLAN_CREATED: '交易计划已生成',
  BUDGET_RESERVED: '预算已预留',
  ENTRY_SUBMITTED: '入场订单已提交',
  ENTRY_FILLED: '入场已成交',
  CONFIRM_REJECTED: '订单确认被拒绝',
};

function formatAutoTradeEnum(value: string) {
  if (!value || value === '-') return '-';
  return translatedLabel(autoTradeEnumLabels, value) ?? value;
}

/** 本地化已知原因码，保留未识别的代码和说明。 */
export function formatAutoTradeReason(value: unknown) {
  if (isMissing(value)) return '-';
  const source = String(value);
  let text = source;
  Object.entries(autoTradeReasonLabels).forEach(([code, label]) => {
    const token = code === 'local_active_entry_order' && text.includes(`${code}:`) ? `${code}:` : code;
    text = text.split(token).join(t(label));
  });
  text = text.replace(/position_quality_score_below_(\d+)/g, t("持仓质量评分低于$1分"));
  text = text.replace(/position_quality_final_rank_below_(\d+)/g, t("最终排名评分低于$1分"));
  text = text.replace(
    /position_quality_capacity_full_regime_(.+?)_max_(\d+)/g,
    (_, regime: string, max: string) => `${t("持仓数量已达到上限（市场状态：")}${formatAutoTradeEnum(regime)}${t("，上限：")}${max}${t("个）")}`,
  );
  text = text.replace(/candidate_action_not_auto_trade_allowed_([A-Z_]+)/g, (_, action: string) =>
    `${t("候选动作不允许自动交易：")}${formatAutoTradeEnum(action)}`,
  );
  text = text.replace(/candidate_signal_not_tradable_([A-Z_]+)/g, (_, signal: string) =>
    `${t("候选信号不可交易：")}${formatAutoTradeEnum(signal)}`,
  );
  text = text.replace(/signal_type_not_tradable_([A-Z_]+)/g, (_, signal: string) =>
    `${t("信号类型不可交易：")}${formatAutoTradeEnum(signal)}`,
  );
  text = text.replace(/same_direction_alt_exposure_limit_(\d+)/g, t("同方向山寨币敞口达到上限（上限：$1个）"));
  text = text.replace(/LIVE_REFRESH_PRICE_DEVIATION_TOO_WIDE_([0-9.]+)%/g, t("实时价格偏离过大（$1%）"));
  text = text.replace(/LIVE_REFRESH_SPREAD_TOO_WIDE_([0-9.]+)bps/g, t("实时价差过大（$1 bps）"));
  text = text.replace(/LIVE_REFRESH_FUNDING_CROWDED_([-0-9.]+)/g, t("实时资金费率拥挤（$1）"));
  text = text.replace(/PRICE_DEVIATION_TOO_WIDE_([0-9.]+)%/g, t("价格偏离过大（$1%）"));
  text = text.replace(/SPREAD_TOO_WIDE_([0-9.]+)%/g, t("价差过大（$1%）"));
  text = text.replace(/FUNDING_CROWDED_([-0-9.]+)/g, t("资金费率拥挤（$1）"));
  text = text.replace(/spread_bps_extreme_?([0-9.]*)/g, (_, bps: string) =>
    bps ? `${t("价差极端（")}${bps} bps${getLanguage() === 'zh-CN' ? '）' : ')'}` : t("价差极端"),
  );
  text = text.replace(/spread_bps_above_([0-9.]+)/g, t("价差超过限制（$1 bps）"));
  text = text.replace(/candidate_budget_allocation_too_low_([0-9.]+)/gi, t("候选预算分配过低（$1 USDT）"));
  text = text.replace(/CANDIDATE_BUDGET_ALLOCATION_TOO_LOW_([0-9.]+)/g, t("候选预算分配过低（$1 USDT）"));
  text = text.replace(/position_quality_passed_regime_(.+?)_min_(\d+)/g, (_, regime: string, min: string) =>
    `${t("持仓质量检查通过（市场状态：")}${formatAutoTradeEnum(regime)}${t("，最低分：")}${min}${getLanguage() === 'zh-CN' ? '）' : ')'}`,
  );
  if (source === text) return t(source);
  return text.replace(/,(?=\s*[A-Za-z_])/g, getLanguage() === 'zh-CN' ? '；' : '; ');
}

export function formatNumber(value: unknown, digits = 4) {
  if (isMissing(value)) return '-';
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '-';
  }
  return new Intl.NumberFormat(getLanguage(), {
    maximumFractionDigits: digits,
    minimumFractionDigits: 0,
  }).format(number);
}

export function formatPrice(value: unknown, digits = 4) {
  if (isMissing(value)) return '-';
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '-';
  }
  // 根据价格大小自动调整精度
  const abs = Math.abs(number);
  if (abs >= 1000) return new Intl.NumberFormat(getLanguage(), { maximumFractionDigits: 2 }).format(number);
  if (abs >= 1) return new Intl.NumberFormat(getLanguage(), { maximumFractionDigits: digits }).format(number);
  if (abs >= 0.01) return new Intl.NumberFormat(getLanguage(), { maximumFractionDigits: 6 }).format(number);
  return new Intl.NumberFormat(getLanguage(), { maximumFractionDigits: 10 }).format(number);
}

export function formatPercent(value: unknown, digits = 2) {
  if (isMissing(value)) return '-';
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '-';
  }
  const sign = number > 0 ? '+' : '';
  return `${sign}${number.toFixed(digits)}%`;
}

export function directionTone(value: unknown): 'success' | 'error' | 'default' {
  const direction = String(value ?? '').toLowerCase();
  if (direction === 'buy' || direction === 'long' || direction === 'bullish') return 'success';
  if (direction === 'sell' || direction === 'short' || direction === 'bearish') return 'error';
  return 'default';
}

export function pnlColor(value: unknown) {
  const number = Number(value);
  return number > 0 ? colors.success : number < 0 ? colors.error : colors.muted;
}

export function formatPercentWithColor(value: unknown, digits = 2): ReactNode {
  if (isMissing(value)) return '-';
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '-';
  }
  const sign = number > 0 ? '+' : '';
  const color = pnlColor(number);
  return createElement(
    'span',
    { style: { color, fontWeight: 650 } },
    `${sign}${number.toFixed(digits)}%`,
  );
}

export function formatVolume(value: unknown) {
  if (isMissing(value)) return '-';
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '-';
  }
  if (number >= 1e9) return `${(number / 1e9).toFixed(2)}B`;
  if (number >= 1e6) return `${(number / 1e6).toFixed(2)}M`;
  if (number >= 1e3) return `${(number / 1e3).toFixed(2)}K`;
  return formatNumber(number);
}

export function formatUSDT(value: unknown) {
  if (isMissing(value)) return '-';
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '-';
  }
  return `$${formatNumber(number, 2)}`;
}

export function formatLeverage(value: unknown) {
  if (isMissing(value)) return '-';
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '-';
  }
  return `${number}x`;
}

function isMissing(value: unknown) {
  return value === null || value === undefined || (typeof value === 'string' && value.trim() === '');
}

export function planSummary(plan: Record<string, unknown>): ReactNode {
  return `${t("计划已生成：")}${formatAction(plan.action)}，${formatSide(plan.orderType)}${t("，入场 ")}${formatPrice(
    plan.entryPrice,
  )}${t("，止损 ")}${formatPrice(plan.stopLossPrice)}${t("，止盈 ")}${formatPrice(plan.takeProfitPrice)}${t("，杠杆 ")}${formatLeverage(
    plan.suggestedLeverage,
  )}${t("，预计数量 ")}${formatNumber(plan.suggestedSize)}`;
}
