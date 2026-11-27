import type { ReactNode } from 'react';
import { Button } from './button';
import { Dialog, DialogContent } from './dialog';

export function ConfirmDialog({
  open,
  title,
  content,
  confirmLabel,
  cancelLabel,
  onConfirm,
  onClose,
  tone = 'primary',
  pending = false,
}: {
  open: boolean;
  title: string;
  content: ReactNode;
  confirmLabel: string;
  cancelLabel: string;
  onConfirm: () => void;
  onClose: () => void;
  tone?: 'primary' | 'danger' | 'warning';
  pending?: boolean;
}) {
  return (
    <Dialog open={open} onOpenChange={(next) => { if (!next && !pending) onClose(); }}>
      <DialogContent title={title} hideClose>
        <div className="text-[14px] leading-6 text-muted">{content}</div>
        <div className="mt-5 flex flex-wrap justify-end gap-2">
          <Button variant="ghost" onClick={onClose} disabled={pending}>{cancelLabel}</Button>
          <Button variant={tone === 'danger' ? 'danger' : tone === 'warning' ? 'warning' : 'primary'} size="sm" onClick={onConfirm} disabled={pending}>{confirmLabel}</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
