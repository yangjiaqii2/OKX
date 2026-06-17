import RefreshIcon from '@mui/icons-material/Refresh';
import { Box, Button, Chip, Stack, Typography } from '@mui/material';
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
  eyebrow?: string;
  status?: ReactNode;
  onRefresh?: () => void;
  refreshing?: boolean;
  actions?: ReactNode;
};

export function PageShell({ title, children, maxWidth = '1540px' }: PageShellProps) {
  return (
    <Box sx={{ px: { xs: 1.5, md: 2.5 }, py: { xs: 1.5, md: 2 }, minHeight: '100%' }}>
      <Title title={title} />
      <Box sx={{ width: 1, maxWidth, mx: 'auto' }}>{children}</Box>
    </Box>
  );
}

export function PageHeader({
  title,
  subtitle,
  eyebrow = 'Live Console',
  status,
  onRefresh,
  refreshing = false,
  actions,
}: PageHeaderProps) {
  return (
    <Stack
      direction={{ xs: 'column', md: 'row' }}
      alignItems={{ xs: 'stretch', md: 'flex-start' }}
      justifyContent="space-between"
      gap={1.5}
      sx={{
        pb: 1.5,
        borderBottom: '1px solid rgba(148, 163, 184, 0.12)',
      }}
    >
      <Stack spacing={0.85} minWidth={0}>
        <Stack direction="row" spacing={0.75} alignItems="center" flexWrap="wrap">
          <Chip size="small" label={eyebrow} variant="outlined" sx={{ width: 'fit-content' }} />
          {status}
        </Stack>
        <Box>
          <Typography
            variant="h5"
            fontWeight={900}
            sx={{
              fontSize: { xs: '1.32rem', md: '1.65rem' },
              letterSpacing: 0,
              lineHeight: 1.15,
            }}
          >
            {title}
          </Typography>
          {subtitle ? (
            <Typography color="text.secondary" variant="body2" sx={{ mt: 0.5, maxWidth: 780 }}>
              {subtitle}
            </Typography>
          ) : null}
        </Box>
      </Stack>

      <Stack direction="row" spacing={0.75} alignItems="center" justifyContent={{ xs: 'flex-start', md: 'flex-end' }} flexWrap="wrap">
        {actions}
        {onRefresh ? (
          <Button
            startIcon={<RefreshIcon />}
            variant="outlined"
            size="small"
            onClick={onRefresh}
            disabled={refreshing}
          >
            {refreshing ? '刷新中' : '刷新'}
          </Button>
        ) : null}
      </Stack>
    </Stack>
  );
}
