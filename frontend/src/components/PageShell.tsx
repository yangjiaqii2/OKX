import RefreshIcon from '@mui/icons-material/Refresh';
import { Box, Button, Stack, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { Title } from 'react-admin';

type PageShellProps = {
  title: string;
  children: ReactNode;
  maxWidth?: number | string;
};

type PageHeaderProps = {
  title: string;
  subtitle?: ReactNode;
  status?: ReactNode;
  onRefresh?: () => void;
  refreshing?: boolean;
  actions?: ReactNode;
  filters?: ReactNode;
};

export function PageShell({ title, children, maxWidth = 1560 }: PageShellProps) {
  return (
    <Box sx={{ px: { xs: 2, sm: 3, xl: 4 }, py: { xs: 2, sm: 3 }, minWidth: 0 }}>
      <Title title={title} />
      <Box sx={{ width: '100%', maxWidth, mx: 'auto', minWidth: 0, overflowWrap: 'anywhere' }}>
        {children}
      </Box>
    </Box>
  );
}

export function PageHeader({ title, subtitle, status, onRefresh, refreshing = false, actions, filters }: PageHeaderProps) {
  return (
    <Stack spacing={2} sx={{ pb: 2, borderBottom: 1, borderColor: 'divider' }}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        alignItems={{ xs: 'stretch', sm: 'flex-start' }}
        justifyContent="space-between"
        gap={2}
      >
        <Box minWidth={0}>
          <Stack direction="row" gap={1} alignItems="center" flexWrap="wrap">
            <Typography component="h1" variant="h4" sx={{ fontSize: { xs: '1.5rem', md: '1.875rem' }, letterSpacing: '-0.025em' }}>
              {title}
            </Typography>
            {status}
          </Stack>
          {subtitle ? (
            <Typography color="text.secondary" variant="body2" sx={{ mt: 1, maxWidth: 760 }}>
              {subtitle}
            </Typography>
          ) : null}
        </Box>

        {actions || onRefresh ? (
          <Stack direction="row" gap={1} alignItems="center" flexWrap="wrap" sx={{ flexShrink: 0 }}>
            {actions}
            {onRefresh ? (
              <Button startIcon={<RefreshIcon />} variant="outlined" onClick={onRefresh} disabled={refreshing}>
                {refreshing ? '刷新中' : '刷新'}
              </Button>
            ) : null}
          </Stack>
        ) : null}
      </Stack>
      {filters ? (
        <Stack direction="row" gap={1} alignItems="center" flexWrap="wrap" minWidth={0}>
          {filters}
        </Stack>
      ) : null}
    </Stack>
  );
}
