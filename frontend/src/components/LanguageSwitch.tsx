import TranslateIcon from '@mui/icons-material/Translate';
import { Button } from '@mui/material';
import { setLanguage, useLanguage } from '../i18n';

export function LanguageSwitch() {
  const language = useLanguage();
  const english = language === 'en-US';
  const target = english ? 'zh-CN' : 'en-US';
  const label = english ? 'Switch to Simplified Chinese' : '切换到英文';

  return (
    <Button
      type="button"
      size="small"
      color="inherit"
      startIcon={<TranslateIcon fontSize="small" />}
      aria-label={label}
      title={label}
      onClick={() => setLanguage(target)}
      sx={{ flexShrink: 0, minHeight: 36, whiteSpace: 'nowrap' }}
    >
      <span lang={target}>{english ? '简体中文' : 'English'}</span>
    </Button>
  );
}
