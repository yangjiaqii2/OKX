import { Slot } from '@radix-ui/react-slot';
import { cva, type VariantProps } from 'class-variance-authority';
import type { ButtonHTMLAttributes } from 'react';
import { cn } from '../../lib/cn';

const buttonVariants = cva(
  'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-lg text-[13px] font-medium transition-[background-color,color,transform,border-color] duration-150 ease-out disabled:pointer-events-none disabled:opacity-45 active:scale-[0.98] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent',
  {
    variants: {
      variant: {
        primary: 'bg-accent text-on-accent hover:brightness-110',
        ghost: 'border border-line-strong bg-transparent text-ink hover:bg-sunken',
        quiet: 'bg-transparent text-ink hover:bg-sunken',
        danger: 'bg-down text-on-accent hover:brightness-110',
        warning: 'border border-warning/40 bg-warning-soft text-warning hover:bg-warning-soft',
      },
      size: {
        md: 'h-12 px-4 text-[15px]',
        sm: 'h-8 px-3',
        icon: 'size-8 px-0',
      },
    },
    defaultVariants: { variant: 'ghost', size: 'sm' },
  },
);

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & VariantProps<typeof buttonVariants> & { asChild?: boolean };

export function Button({ className, variant, size, asChild, type = 'button', ...props }: ButtonProps) {
  const Comp = asChild ? Slot : 'button';
  return <Comp className={cn(buttonVariants({ variant, size }), className)} type={asChild ? undefined : type} {...props} />;
}
