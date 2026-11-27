import * as DialogPrimitive from '@radix-ui/react-dialog';
import { X } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '../../lib/cn';
import { t } from '../../i18n';

export function Dialog({ open, onOpenChange, children }: { open: boolean; onOpenChange: (open: boolean) => void; children: ReactNode }) {
  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      {children}
    </DialogPrimitive.Root>
  );
}

export function DialogContent({
  title,
  description,
  children,
  className,
  hideClose = false,
}: {
  title: ReactNode;
  description?: ReactNode;
  children: ReactNode;
  className?: string;
  hideClose?: boolean;
}) {
  return (
    <DialogPrimitive.Portal>
      <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-[var(--overlay)]" />
      <DialogPrimitive.Content
        className={cn(
          'fixed left-1/2 top-1/2 z-50 max-h-[min(88dvh,820px)] w-[min(560px,calc(100vw-32px))] -translate-x-1/2 -translate-y-1/2 overflow-auto rounded-xl bg-raised p-5 text-ink shadow-[0_1px_1px_rgba(16,20,28,0.05),0_16px_40px_rgba(16,20,28,0.12)] focus:outline-none',
          className,
        )}
      >
        <div className="mb-4 flex items-start justify-between gap-4">
          <div className="min-w-0">
            <DialogPrimitive.Title className="text-base font-semibold tracking-[-0.011em]">{title}</DialogPrimitive.Title>
            {description ? <DialogPrimitive.Description className="mt-1 text-[13px] leading-5 text-muted">{description}</DialogPrimitive.Description> : null}
          </div>
          {hideClose ? null : (
            <DialogPrimitive.Close className="grid size-8 place-items-center rounded-lg text-muted hover:bg-sunken" aria-label={t('关闭')}>
              <X size={16} />
            </DialogPrimitive.Close>
          )}
        </div>
        {children}
      </DialogPrimitive.Content>
    </DialogPrimitive.Portal>
  );
}
