import { colors } from '../designTokens';
import ShieldIcon from '@mui/icons-material/Shield';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import { Alert, Box, Card, CardContent, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { formatLeverage, formatStatus, formatUSDT } from '../formatters';
import { insetPanel, panel } from './surfaces';
import { StatusChip } from './StatusChip';
import { t, useLanguage } from '../i18n';

type RiskCommandPanelProps = {
  risk?: Record<string, unknown>;
  account?: Record<string, unknown>;
  pendingCount: number | null;
};

export function RiskCommandPanel({ risk = {}, account = {}, pendingCount }: RiskCommandPanelProps) {
  useLanguage();
  const riskLevel = String(risk.riskLevel ?? 'UNKNOWN');
  const passed = risk.passed as boolean | undefined;
  const accountMode = String(account.mode ?? 'UNKNOWN');
  const rejectReason = String(risk.rejectReason ?? '');
  const createdAt = String(risk.createdAt ?? '');
  const isBlocked = passed === false || riskLevel === 'HIGH';

  return (
    <Card
      sx={{
        ...panel,
        height: '100%',
        borderColor: isBlocked ? colors.errorBorder : colors.warningBorder,
        background: isBlocked ? colors.errorSoft : colors.paper,
      }}
    >
      <CardContent sx={{ p: 2, '&:last-child': { pb: 2 } }}>
        <Stack spacing={1.6}>
          <Stack direction="row" justifyContent="space-between" alignItems="flex-start" gap={1.5}>
            <Stack spacing={0.5}>
              <Typography variant="caption" color="text.secondary" fontWeight={800} sx={{ textTransform: 'uppercase' }}>
                {t("风控状态")}
              </Typography>
              <Typography variant="h5" fontWeight={900} sx={{ lineHeight: 1.1 }}>
                {formatStatus(riskLevel)}
              </Typography>
            </Stack>
            <Box
              sx={{
                width: 40,
                height: 40,
                display: 'grid',
                placeItems: 'center',
                borderRadius: 1,
                color: isBlocked ? 'error.main' : 'warning.main',
                bgcolor: isBlocked ? colors.errorSoft : colors.warningSoft,
                border: 1, borderColor: 'divider',
              }}
            >
              {isBlocked ? <WarningAmberIcon /> : <ShieldIcon />}
            </Box>
          </Stack>

          <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 1 }}>
            <MiniStat label={t("风控")} value={<StatusChip value={passed} />} />
            <MiniStat label={t("账户")} value={<StatusChip value={accountMode} />} />
            <MiniStat label={t("建议杠杆")} value={formatLeverage(risk.adjustedLeverage)} />
            <MiniStat label={t("单笔亏损")} value={formatUSDT(risk.maxLossAmount)} />
            <MiniStat label={t("待确认")} value={pendingCount ?? t("不可用")} tone={pendingCount === 0 ? 'neutral' : 'warning'} />
            <MiniStat label={t("检查时间")} value={createdAt ? createdAt.slice(11, 19) : '-'} />
          </Box>

          {rejectReason ? <Alert severity={isBlocked ? 'error' : 'warning'}>{rejectReason}</Alert> : null}
          {accountMode === 'OKX_UNBOUND' ? <Alert severity="warning">{t("OKX API 未绑定，私有交易接口不可用。")}</Alert> : null}
          {accountMode === 'OKX_ERROR' ? <Alert severity="error">{String(account.message ?? t("OKX接口异常"))}</Alert> : null}
        </Stack>
      </CardContent>
    </Card>
  );
}

function MiniStat({ label, value, tone = 'neutral' }: { label: string; value: ReactNode; tone?: 'warning' | 'neutral' }) {
  useLanguage();
  return (
    <Box
      sx={{
        ...insetPanel,
        p: 1,
        borderRadius: 1,
        bgcolor: tone === 'warning' ? colors.warningSoft : colors.subtle,
        minHeight: 58,
      }}
    >
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography component="div" fontWeight={800} sx={{ mt: 0.5 }}>
        {value}
      </Typography>
    </Box>
  );
}
