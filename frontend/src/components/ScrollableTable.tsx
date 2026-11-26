import SwapHorizIcon from '@mui/icons-material/SwapHoriz';
import { Box, Stack, Table, TableContainer, Typography } from '@mui/material';
import { useEffect, useId, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { colors } from '../designTokens';
import { t, useLanguage } from '../i18n';

export function ScrollableTable({ label, children }: { label: string; children: ReactNode }) {
  useLanguage();
  const region = useRef<HTMLDivElement>(null);
  const table = useRef<HTMLTableElement>(null);
  const hintId = useId();
  const [overflowing, setOverflowing] = useState(false);

  useEffect(() => {
    const container = region.current;
    const content = table.current;
    if (!container || !content) return;
    const observer = new ResizeObserver(() => setOverflowing(container.scrollWidth > container.clientWidth + 1));
    observer.observe(container);
    observer.observe(content);
    return () => observer.disconnect();
  }, []);

  return (
    <Box sx={{ minWidth: 0 }}>
      {overflowing ? (
        <Stack id={hintId} direction="row" alignItems="center" gap={0.5} sx={{ px: 2, py: 1, color: 'text.secondary' }}>
          <SwapHorizIcon fontSize="small" aria-hidden />
          <Typography variant="caption">{t("横向滚动查看更多")}</Typography>
        </Stack>
      ) : null}
      <TableContainer
        ref={region}
        role="region"
        aria-label={label}
        aria-describedby={overflowing ? hintId : undefined}
        tabIndex={0}
        sx={{
          width: '100%',
          maxHeight: 'min(64dvh, 640px)',
          overflow: 'auto',
          '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main', outlineOffset: -2 },
        }}
      >
        <Table ref={table} size="small" stickyHeader sx={{
          width: 'max-content',
          minWidth: '100%',
          '& .MuiTableCell-body': { maxWidth: 320, overflowWrap: 'anywhere' },
          '& tbody tr:nth-of-type(even)': { bgcolor: colors.subtle },
          '& tbody tr:hover': { bgcolor: 'action.selected' },
        }}>
          {children}
        </Table>
      </TableContainer>
    </Box>
  );
}
