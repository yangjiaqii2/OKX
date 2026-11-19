import InboxOutlinedIcon from '@mui/icons-material/InboxOutlined';
import { Box, CircularProgress, Stack, Typography } from '@mui/material';
import type { SxProps, Theme } from '@mui/material/styles';
import type { ReactNode } from 'react';

type PanelStateProps = {
  children?: ReactNode;
  loading?: boolean;
  sx?: SxProps<Theme>;
};

export function PanelState({ children, loading = false, sx = [] }: PanelStateProps) {
  return (
    <Stack alignItems="center" justifyContent="center" spacing={1} role="status" sx={[
      { minHeight: 160, px: 2, py: 3, textAlign: 'center' },
      ...(Array.isArray(sx) ? sx : [sx]),
    ]}>
      {loading ? <CircularProgress size={28} aria-label="加载中" /> : (
        <>
          <Box sx={{ color: 'primary.main', bgcolor: 'action.selected', p: 1, borderRadius: 1, display: 'flex' }}>
            <InboxOutlinedIcon fontSize="small" aria-hidden />
          </Box>
          <Typography variant="body2" color="text.secondary">{children}</Typography>
        </>
      )}
    </Stack>
  );
}
