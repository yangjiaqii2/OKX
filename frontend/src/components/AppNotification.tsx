import { useEffect, useState } from 'react';
import { useNotificationContext, useTranslate, type NotificationPayload } from 'ra-core';
import { cn } from '../lib/cn';

export function AppNotification() {
  const { notifications, takeNotification } = useNotificationContext();
  const translate = useTranslate();
  const [current, setCurrent] = useState<NotificationPayload | undefined>();

  useEffect(() => {
    if (!current && notifications.length) {
      const next = takeNotification();
      if (next) setCurrent(next);
    }
  }, [notifications, current, takeNotification]);

  useEffect(() => {
    if (!current) return;
    const duration = current.notificationOptions?.autoHideDuration ?? 4000;
    const timer = window.setTimeout(() => setCurrent(undefined), duration);
    return () => window.clearTimeout(timer);
  }, [current]);

  if (!current) return null;
  const type = current.type ?? 'info';
  const message = typeof current.message === 'string'
    ? translate(current.message, { _: current.message, ...current.notificationOptions?.messageArgs })
    : current.message;
  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-20 z-[60] flex justify-center px-4 md:bottom-6">
      <div
        role="status"
        className={cn(
          'pointer-events-auto max-w-md rounded-lg px-3.5 py-2.5 text-[13px] leading-5 shadow-[0_8px_24px_rgba(16,20,28,0.12)]',
          type === 'success' && 'bg-up-soft text-up',
          type === 'warning' && 'bg-warning-soft text-warning',
          type === 'error' && 'bg-down-soft text-down',
          type !== 'success' && type !== 'warning' && type !== 'error' && 'bg-raised text-ink',
        )}
      >
        {message}
      </div>
    </div>
  );
}
