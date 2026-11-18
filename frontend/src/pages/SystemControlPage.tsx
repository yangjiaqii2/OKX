import EmergencyIcon from '@mui/icons-material/Emergency';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import PowerSettingsNewIcon from '@mui/icons-material/PowerSettingsNew';
import SmartToyIcon from '@mui/icons-material/SmartToy';
import {
  Alert,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { Confirm, useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { PageHeader, PageShell } from '../components/PageShell';

type SystemStatus = {
  emergencyStop?: boolean;
  autoTradeEnabled?: boolean;
  autoTradeMarginUsdt?: number | string;
  autoTradeBudgetMode?: AutoTradeBudgetMode;
  autoTradeMarginPct?: number | string;
  autoTradeRiskMode?: AutoTradeRiskMode;
  noRiskMinScore?: number | string;
  autoTradeMinLeverage?: number | string;
  updatedAt?: string;
};

type AutoTradeRiskMode = 'STRICT' | 'NO_RISK';
type AutoTradeBudgetMode = 'FIXED_USDT' | 'AVAILABLE_BALANCE_PERCENT';

function usdtAmount(value: unknown) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number.toLocaleString('zh-CN', { maximumFractionDigits: 2 }) : '--';
}

export function SystemControlPage() {
  const notify = useNotify();
  const [emergencyOpen, setEmergencyOpen] = useState(false);
  const [resumeOpen, setResumeOpen] = useState(false);
  const [autoEnableOpen, setAutoEnableOpen] = useState(false);
  const [autoDisableOpen, setAutoDisableOpen] = useState(false);
  const [status, setStatus] = useState<SystemStatus>({});
  const [autoTradeMarginUsdt, setAutoTradeMarginUsdt] = useState('100');
  const [autoTradeBudgetMode, setAutoTradeBudgetMode] = useState<AutoTradeBudgetMode>('FIXED_USDT');
  const [autoTradeRiskMode, setAutoTradeRiskMode] = useState<AutoTradeRiskMode>('STRICT');
  const [noRiskMinScore, setNoRiskMinScore] = useState('70');
  const [autoTradeMinLeverage, setAutoTradeMinLeverage] = useState('1');
  const [submitting, setSubmitting] = useState(false);

  async function loadStatus() {
    try {
      const nextStatus = (await quantApi.systemStatus()) as SystemStatus;
      setStatus(nextStatus);
      const budgetValue = nextStatus.autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT'
        ? Number(nextStatus.autoTradeMarginPct ?? 0)
        : Number(nextStatus.autoTradeMarginUsdt ?? 0);
      if (Number.isFinite(budgetValue) && budgetValue > 0) {
        setAutoTradeMarginUsdt(String(budgetValue));
      }
      if (nextStatus.autoTradeBudgetMode === 'FIXED_USDT'
        || nextStatus.autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT') {
        setAutoTradeBudgetMode(nextStatus.autoTradeBudgetMode);
      }
      if (nextStatus.autoTradeRiskMode === 'STRICT' || nextStatus.autoTradeRiskMode === 'NO_RISK') {
        setAutoTradeRiskMode(nextStatus.autoTradeRiskMode);
      }
      const score = Number(nextStatus.noRiskMinScore ?? 0);
      if (Number.isFinite(score) && score >= 60 && score <= 100) {
        setNoRiskMinScore(String(score));
      }
      const minLeverage = Number(nextStatus.autoTradeMinLeverage ?? 0);
      if (Number.isInteger(minLeverage) && minLeverage >= 1 && minLeverage <= 20) {
        setAutoTradeMinLeverage(String(minLeverage));
      }
    } catch (error) {
      notify(error instanceof Error ? error.message : '系统状态加载失败', { type: 'error' });
    }
  }

  async function emergencyStop() {
    setSubmitting(true);
    try {
      setStatus((await quantApi.emergencyStop()) as SystemStatus);
      notify('已触发紧急停止', { type: 'warning' });
    } catch (error) {
      notify(error instanceof Error ? error.message : '紧急停止失败', { type: 'error' });
    } finally {
      setSubmitting(false);
      setEmergencyOpen(false);
    }
  }

  async function resume() {
    setSubmitting(true);
    try {
      setStatus((await quantApi.resume()) as SystemStatus);
      notify('系统已恢复运行', { type: 'success' });
    } catch (error) {
      notify(error instanceof Error ? error.message : '恢复失败', { type: 'error' });
    } finally {
      setSubmitting(false);
      setResumeOpen(false);
    }
  }

  async function enableAutoTrade() {
    const budgetValue = Number(autoTradeMarginUsdt);
    const validBudget = autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT'
      ? Number.isFinite(budgetValue) && budgetValue > 0 && budgetValue <= 100
      : Number.isFinite(budgetValue) && budgetValue > 0;
    if (!validBudget) {
      notify(autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT'
        ? '可用余额百分比必须大于0且不超过100%'
        : '自动交易总预算必须大于0', { type: 'warning' });
      return;
    }
    const score = Number(noRiskMinScore);
    if (autoTradeRiskMode === 'NO_RISK' && (!Number.isInteger(score) || score < 60 || score > 100)) {
      notify('Shadow 最低观察分数必须是60到100之间的整数', { type: 'warning' });
      return;
    }
    const minLeverage = Number(autoTradeMinLeverage);
    if (!Number.isInteger(minLeverage) || minLeverage < 1 || minLeverage > 20) {
      notify('自动交易最低杠杆倍数必须是1到20之间的整数', { type: 'warning' });
      return;
    }
    setSubmitting(true);
    try {
      setStatus((await quantApi.enableAutoTrade(
        String(budgetValue),
        autoTradeRiskMode,
        autoTradeRiskMode === 'NO_RISK' ? String(score) : undefined,
        String(minLeverage),
        autoTradeBudgetMode,
      )) as SystemStatus);
      notify(autoTradeRiskMode === 'NO_RISK'
        ? `Shadow 观察已开启：最低分 ${score}，不会创建或提交订单`
        : autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT'
          ? `自动交易已开启：使用可用余额 ${budgetValue}% 动态预算，严格风控`
          : `自动交易已开启：总预算 ${budgetValue} USDT，严格风控`, { type: 'warning' });
    } catch (error) {
      notify(error instanceof Error ? error.message : '自动交易开启失败', { type: 'error' });
    } finally {
      setSubmitting(false);
      setAutoEnableOpen(false);
    }
  }

  async function disableAutoTrade() {
    setSubmitting(true);
    try {
      setStatus((await quantApi.disableAutoTrade()) as SystemStatus);
      notify('自动交易已关闭', { type: 'success' });
    } catch (error) {
      notify(error instanceof Error ? error.message : '自动交易关闭失败', { type: 'error' });
    } finally {
      setSubmitting(false);
      setAutoDisableOpen(false);
    }
  }

  useEffect(() => {
    void loadStatus();
  }, []);

  return (
    <PageShell title="系统控制" maxWidth={980}>
      <Stack spacing={2.5} maxWidth={900}>
        <PageHeader
          title="系统控制"
          subtitle="集中放置影响交易流程的系统级动作。"
          eyebrow="Control Room"
        />
        <Alert severity="warning">紧急停止不会删除数据，但会切换后端运行状态。实盘环境下请先确认当前OKX未留有未处理订单。</Alert>
        <Card>
          <CardContent>
            <Stack spacing={2}>
              <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" gap={1.5}>
                <Typography variant="h6">运行开关</Typography>
                <Stack direction="row" gap={1} flexWrap="wrap">
                  <Chip
                    size="small"
                    color={status.emergencyStop ? 'error' : 'success'}
                    label={status.emergencyStop ? '紧急停止' : '运行中'}
                  />
                  <Chip
                    size="small"
                    color={status.autoTradeEnabled ? 'warning' : 'default'}
                    label={status.autoTradeEnabled
                      ? status.autoTradeRiskMode === 'NO_RISK' ? 'Shadow 运行中' : '自动交易开启'
                      : '自动交易关闭'}
                  />
                  <Chip
                    size="small"
                    color={(status.autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT'
                      ? Number(status.autoTradeMarginPct ?? 0)
                      : Number(status.autoTradeMarginUsdt ?? 0)) > 0 ? 'info' : 'default'}
                    label={status.autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT'
                      ? `动态预算 ${status.autoTradeMarginPct ?? '--'}%`
                      : `固定预算 ${usdtAmount(status.autoTradeMarginUsdt)} USDT`}
                  />
                  <Chip
                    size="small"
                    color={status.autoTradeRiskMode === 'NO_RISK' ? 'info' : 'success'}
                    label={status.autoTradeRiskMode === 'NO_RISK' ? 'Shadow 观察' : '严格风控'}
                  />
                  {status.autoTradeRiskMode === 'NO_RISK' ? (
                    <Chip
                      size="small"
                      color="info"
                      variant="outlined"
                      label={`最低分 ${status.noRiskMinScore ?? noRiskMinScore}`}
                    />
                  ) : null}
                  <Chip
                    size="small"
                    color="info"
                    variant="outlined"
                    label={`最低杠杆 ${status.autoTradeMinLeverage ?? autoTradeMinLeverage}x`}
                  />
                </Stack>
              </Stack>
              <Stack direction={{ xs: 'column', sm: 'row' }} gap={1.5}>
                <Button startIcon={<EmergencyIcon />} variant="contained" color="error" disabled={submitting} onClick={() => setEmergencyOpen(true)}>
                  紧急停止
                </Button>
                <Button startIcon={<PlayArrowIcon />} variant="outlined" disabled={submitting} onClick={() => setResumeOpen(true)}>
                  恢复运行
                </Button>
              </Stack>
              <Divider />
              <Stack direction={{ xs: 'column', sm: 'row' }} gap={1.5} flexWrap="wrap" sx={{ width: 1, minWidth: 0 }}>
                <TextField
                  size="small"
                  label={autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT' ? '可用余额比例' : '自动交易总预算'}
                  value={autoTradeMarginUsdt}
                  onChange={(event) => setAutoTradeMarginUsdt(event.target.value.replace(/[^\d.]/g, ''))}
                  disabled={submitting || Boolean(status.autoTradeEnabled)}
                  inputProps={{ inputMode: 'decimal', min: 0, max: autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT' ? 100 : undefined, step: '0.01' }}
                  sx={{ width: { xs: 1, sm: 220 } }}
                  InputProps={{ endAdornment: <Typography variant="caption" color="text.secondary">{autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT' ? '%' : 'USDT'}</Typography> }}
                />
                <ToggleButtonGroup
                  size="small"
                  exclusive
                  value={autoTradeBudgetMode}
                  disabled={submitting || Boolean(status.autoTradeEnabled)}
                  onChange={(_, value: AutoTradeBudgetMode | null) => {
                    if (value) {
                      setAutoTradeBudgetMode(value);
                    }
                  }}
                >
                  <ToggleButton value="FIXED_USDT">固定金额</ToggleButton>
                  <ToggleButton value="AVAILABLE_BALANCE_PERCENT">余额比例</ToggleButton>
                </ToggleButtonGroup>
                <ToggleButtonGroup
                  size="small"
                  exclusive
                  value={autoTradeRiskMode}
                  disabled={submitting || Boolean(status.autoTradeEnabled)}
                  onChange={(_, value: AutoTradeRiskMode | null) => {
                    if (value) {
                      setAutoTradeRiskMode(value);
                    }
                  }}
                  sx={{
                    '& .MuiToggleButton-root': {
                      px: 1.5,
                      minWidth: 92,
                      whiteSpace: 'nowrap',
                    },
                  }}
                >
                  <ToggleButton value="STRICT">严格风控</ToggleButton>
                  <ToggleButton value="NO_RISK" color="info">Shadow 观察</ToggleButton>
                </ToggleButtonGroup>
                {autoTradeRiskMode === 'NO_RISK' ? (
                  <TextField
                    size="small"
                    label="Shadow 最低分"
                    value={noRiskMinScore}
                    onChange={(event) => setNoRiskMinScore(event.target.value.replace(/[^\d]/g, '').slice(0, 3))}
                    disabled={submitting || Boolean(status.autoTradeEnabled)}
                    inputProps={{ inputMode: 'numeric', min: 60, max: 100, step: 1 }}
                    sx={{ width: { xs: 1, sm: 150 } }}
                    InputProps={{ endAdornment: <Typography variant="caption" color="text.secondary">分</Typography> }}
                  />
                ) : null}
                <TextField
                  size="small"
                  label="最低杠杆"
                  value={autoTradeMinLeverage}
                  onChange={(event) => setAutoTradeMinLeverage(event.target.value.replace(/[^\d]/g, '').slice(0, 2))}
                  disabled={submitting || Boolean(status.autoTradeEnabled)}
                  inputProps={{ inputMode: 'numeric', min: 1, max: 20, step: 1 }}
                  sx={{ width: { xs: 1, sm: 130 } }}
                  InputProps={{ endAdornment: <Typography variant="caption" color="text.secondary">x</Typography> }}
                />
                <Button
                  startIcon={<SmartToyIcon />}
                  variant="contained"
                  color="warning"
                  disabled={submitting || Boolean(status.emergencyStop) || Boolean(status.autoTradeEnabled)}
                  onClick={() => setAutoEnableOpen(true)}
                  sx={{ whiteSpace: 'nowrap' }}
                >
                  {autoTradeRiskMode === 'NO_RISK' ? '开启 Shadow' : '开启自动交易'}
                </Button>
                <Button
                  startIcon={<PowerSettingsNewIcon />}
                  variant="outlined"
                  disabled={submitting || !status.autoTradeEnabled}
                  onClick={() => setAutoDisableOpen(true)}
                  sx={{ whiteSpace: 'nowrap' }}
                >
                  关闭自动交易
                </Button>
              </Stack>
            </Stack>
          </CardContent>
        </Card>
        <Confirm
          isOpen={emergencyOpen}
          title="触发紧急停止"
          content="确认后后端会切换到紧急停止状态。请先确认当前 OKX 没有未处理订单。"
          confirm="紧急停止"
          cancel="返回"
          onConfirm={() => void emergencyStop()}
          onClose={() => setEmergencyOpen(false)}
        />
        <Confirm
          isOpen={resumeOpen}
          title="恢复系统运行"
          content="确认恢复后，扫描和交易流程将重新按后端配置运行。"
          confirm="恢复运行"
          cancel="返回"
          onConfirm={() => void resume()}
          onClose={() => setResumeOpen(false)}
        />
        <Confirm
          isOpen={autoEnableOpen}
          title={autoTradeRiskMode === 'NO_RISK' ? '开启 Shadow 观察' : '开启自动交易'}
          content={`确认后，${autoTradeBudgetMode === 'AVAILABLE_BALANCE_PERCENT' ? `每轮使用最新可用余额的 ${autoTradeMarginUsdt || '--'}%` : `${autoTradeMarginUsdt || '--'} USDT`} 作为自动交易预算。当前模式为 ${autoTradeRiskMode === 'NO_RISK' ? `Shadow 观察：记录${noRiskMinScore || '--'}分以上候选的策略输出，不创建 PendingOrder，也不发送任何 OKX 写请求` : '严格风控：保持当前AUTO_TRADE_ALLOWED门槛'}。`}
          confirm="开启"
          cancel="返回"
          onConfirm={() => void enableAutoTrade()}
          onClose={() => setAutoEnableOpen(false)}
        />
        <Confirm
          isOpen={autoDisableOpen}
          title="关闭自动交易"
          content="确认后，后端不会再自动提交新订单，已提交的 OKX 订单需要继续在 OKX 或持仓页检查。"
          confirm="关闭"
          cancel="返回"
          onConfirm={() => void disableAutoTrade()}
          onClose={() => setAutoDisableOpen(false)}
        />
      </Stack>
    </PageShell>
  );
}
