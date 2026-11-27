import { ArrowLeftRight } from 'lucide-react';
import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
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
    <div className="min-w-0">
      {overflowing ? (
        <p id={hintId} className="mb-1 flex items-center gap-1.5 text-[12px] text-muted">
          <ArrowLeftRight size={14} aria-hidden />
          {t('横向滚动查看更多')}
        </p>
      ) : null}
      <div
        ref={region}
        role="region"
        aria-label={label}
        aria-describedby={overflowing ? hintId : undefined}
        tabIndex={0}
        className="max-h-[min(64dvh,640px)] w-full overflow-auto focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent"
      >
        <table ref={table} className="w-max min-w-full border-collapse">
          {children}
        </table>
      </div>
    </div>
  );
}
