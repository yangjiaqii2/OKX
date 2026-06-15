import EmergencyIcon from '@mui/icons-material/Emergency';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import { Alert, Button, Card, CardContent, Stack, Typography } from '@mui/material';
import { useNotify } from 'react-admin';
import { quantApi } from '../api/quantApi';
import { PageHeader, PageShell } from '../components/PageShell';

export function SystemControlPage() {
  const notify = useNotify();

  async function emergencyStop() {
    try {
      await quantApi.emergencyStop();
      notify('已触发紧急停止', { type: 'warning' });
    } catch (error) {
      notify(error instanceof Error ? error.message : '紧急停止失败', { type: 'error' });
    }
  }

  async function resume() {
    try {
      await quantApi.resume();
      notify('系统已恢复运行', { type: 'success' });
    } catch (error) {
      notify(error instanceof Error ? error.message : '恢复失败', { type: 'error' });
    }
  }

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
              <Typography variant="h6">运行开关</Typography>
              <Stack direction={{ xs: 'column', sm: 'row' }} gap={1.5}>
                <Button startIcon={<EmergencyIcon />} variant="contained" color="error" onClick={() => void emergencyStop()}>
                  紧急停止
                </Button>
                <Button startIcon={<PlayArrowIcon />} variant="outlined" onClick={() => void resume()}>
                  恢复运行
                </Button>
              </Stack>
            </Stack>
          </CardContent>
        </Card>
      </Stack>
    </PageShell>
  );
}
