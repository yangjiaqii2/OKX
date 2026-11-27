import { createContext, useContext, type HTMLAttributes, type ReactNode, type TdHTMLAttributes, type ThHTMLAttributes } from 'react';
import { cn } from '../../lib/cn';

const HeadContext = createContext(false);

export function TableHead({ children }: { children: ReactNode }) {
  return (
    <thead>
      <HeadContext.Provider value={true}>{children}</HeadContext.Provider>
    </thead>
  );
}

export function TableBody({ children }: { children: ReactNode }) {
  return <tbody>{children}</tbody>;
}

export function TableRow({ children, className }: { children: ReactNode; className?: string }) {
  return <tr className={cn('border-b border-line', className)}>{children}</tr>;
}

type CellProps = {
  children?: ReactNode;
  align?: 'left' | 'right';
  className?: string;
  style?: TdHTMLAttributes<HTMLTableCellElement>['style'];
  colSpan?: number;
};

export function TableCell({ children, align = 'left', className, style, colSpan }: CellProps) {
  const head = useContext(HeadContext);
  const classes = cn(
    'px-0 py-3 pr-3 align-middle text-[13px] leading-[18px] tracking-[-0.011em] tabular-nums',
    head && 'pb-2 pt-0 text-[12px] font-medium text-muted',
    align === 'right' && 'text-right',
    className,
  );
  if (head) {
    return <th className={classes} style={style as ThHTMLAttributes<HTMLTableCellElement>['style']} colSpan={colSpan}>{children}</th>;
  }
  return <td className={classes} style={style} colSpan={colSpan}>{children}</td>;
}

export function Table({ children, className, style, ...props }: HTMLAttributes<HTMLTableElement>) {
  return <table className={cn('w-full border-collapse', className)} style={style} {...props}>{children}</table>;
}
