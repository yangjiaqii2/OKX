import type { InputHTMLAttributes, ReactNode, SelectHTMLAttributes } from 'react';
import { cn } from '../../lib/cn';

export function Field({ label, hint, error, children, className }: { label?: ReactNode; hint?: ReactNode; error?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <label className={cn('block min-w-0', className)}>
      {label ? <span className="mb-1.5 block text-[13px] font-medium leading-[18px]">{label}</span> : null}
      {children}
      {error ? <span className="mt-1.5 block text-[12px] leading-4 text-down">{error}</span> : hint ? <span className="mt-1.5 block text-[12px] leading-4 text-muted">{hint}</span> : null}
    </label>
  );
}

const control = 'h-12 w-full rounded-lg border border-line-strong bg-field px-3.5 text-[15px] text-ink outline-none transition-[border-color,box-shadow] placeholder:text-faint focus:border-accent focus:shadow-[0_0_0_3px_var(--accent-soft)] disabled:opacity-50';

export function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={cn(control, 'font-medium tracking-[-0.011em]', className)} {...props} />;
}

export function SelectInput({ className, children, ...props }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select className={cn(control, 'h-8 px-2.5 text-[13px] font-medium', className)} {...props}>
      {children}
    </select>
  );
}
