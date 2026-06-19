import type { ReactNode } from 'react';
import { createElement } from 'react';

export function formatBoolean(value: unknown) {
  return value === true || value === 'true' ? '是' : '否';
}

export function formatTrend(value: unknown) {
  const text = String(value ?? '');
  const map: Record<string, string> = {
    BULLISH: '多头趋势',
    BEARISH: '空头趋势',
    NEUTRAL: '震荡观望',
    UNKNOWN: '未知',
  };
  return map[text] ?? (text || '-');
}

export function formatAction(value: unknown) {
  const text = String(value ?? '');
  const map: Record<string, string> = {
    OPEN_LONG: '开多',
    OPEN_SHORT: '开空',
    CLOSE_LONG: '平多',
    CLOSE_SHORT: '平空',
  };
  return map[text] ?? (text || '-');
}

export function formatSide(value: unknown) {
  const text = String(value ?? '');
  const map: Record<string, string> = {
    buy: '买入',
    sell: '卖出',
    long: '多仓',
    short: '空仓',
    cross: '全仓',
    isolated: '逐仓',
    LIMIT: '限价',
    MARKET: '市价',
    limit: '限价',
    market: '市价',
  };
  return map[text] ?? (text || '-');
}

export function formatStatus(value: unknown) {
  const text = String(value ?? '');
  const map: Record<string, string> = {
    true: '通过',
    false: '拦截',
    PENDING_CONFIRM: '待确认',
    CONFIRMED: '已确认',
    SUBMITTED: '入场委托已提交',
    ENTRY_SUBMITTED: '入场委托已提交',
    ENTRY_FILLED: '入场已成交',
    ENTRY_PARTIAL_FILLED: '入场部分成交',
    ENTRY_PENDING: '等待成交',
    UNKNOWN_SUBMIT_STATUS: '提交未知',
    ENTRY_TIMEOUT_CANCELLED: '入场超时已撤',
    PROTECTION_SUBMITTED: '保护单已提交',
    PROTECTION_FAILED: '保护单失败',
    SIDEWAYS_TIMEOUT: '横盘超时',
    SIDEWAYS_TIMEOUT_TP_ADJUSTED: '横盘TP已调整',
    MAX_HOLD_TIMEOUT: '最大持仓超时',
    CLOSE_SUBMITTED: '平仓已提交',
    CLOSED: '已关闭',
    EMERGENCY_ATTENTION_REQUIRED: '紧急处理',
    EXECUTED: '已执行',
    REJECTED: '已拒绝',
    FAILED: '失败',
    SKIPPED: '已跳过',
    CANCELLED: '已取消',
    EXPIRED: '已过期',
    LOW: '低风险',
    MEDIUM: '中风险',
    HIGH: '高风险',
    BLOCKED: '已拦截',
    OKX_REAL: 'OKX实盘',
    OKX_ERROR: 'OKX异常',
    OKX_UNBOUND: '未绑定',
    OKX_EMPTY: '账户为空',
  };
  return map[text] ?? (text || '-');
}

export function formatNumber(value: unknown, digits = 4) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '-';
  }
  return new Intl.NumberFormat('zh-CN', {
    maximumFractionDigits: digits,
    minimumFractionDigits: 0,
  }).format(number);
}

export function formatPrice(value: unknown, digits = 4) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '-';
  }
  // 根据价格大小自动调整精度
  const abs = Math.abs(number);
  if (abs >= 1000) return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(number);
  if (abs >= 1) return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: digits }).format(number);
  if (abs >= 0.01) return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 6 }).format(number);
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 10 }).format(number);
}

export function formatPercent(value: unknown, digits = 2) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '-';
  }
  const sign = number > 0 ? '+' : '';
  return `${sign}${number.toFixed(digits)}%`;
}

export function formatPercentWithColor(value: unknown, digits = 2): ReactNode {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '-';
  }
  const sign = number > 0 ? '+' : '';
  const color = number > 0 ? '#22c55e' : number < 0 ? '#ef4444' : '#94a3b8';
  return createElement(
    'span',
    { style: { color, fontWeight: 600, fontFamily: '"JetBrains Mono", monospace' } },
    `${sign}${number.toFixed(digits)}%`,
  );
}

export function formatVolume(value: unknown) {
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
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '-';
  }
  return `$${formatNumber(number, 2)}`;
}

export function formatLeverage(value: unknown) {
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '-';
  }
  return `${number}x`;
}

export function planSummary(plan: Record<string, unknown>): ReactNode {
  return `计划已生成：${formatAction(plan.action)}，${formatSide(plan.orderType)}，入场 ${formatPrice(
    plan.entryPrice,
  )}，止损 ${formatPrice(plan.stopLossPrice)}，止盈 ${formatPrice(plan.takeProfitPrice)}，杠杆 ${formatLeverage(
    plan.suggestedLeverage,
  )}，预计数量 ${formatNumber(plan.suggestedSize)}`;
}
