import { cn } from '../lib/cn';

type BrandMarkProps = {
  tone?: 'forest' | 'light' | 'lime';
  size?: number;
  className?: string;
};

export function BrandMark({ tone = 'forest', size = 28, className }: BrandMarkProps) {
  const isLime = tone === 'lime';
  const isLight = tone === 'light';

  const containerStyle = isLime
    ? 'bg-[#D6EE8A] ring-1 ring-[#13261B]/15'
    : isLight
      ? 'bg-gradient-to-br from-[#275A44] via-[#16382B] to-[#0D2118] ring-1 ring-white/25 shadow-[0_2px_10px_rgba(0,0,0,0.22)]'
      : 'bg-gradient-to-br from-[#234F3C] via-[#143326] to-[#0D2118] ring-1 ring-[#D6EE8A]/25 shadow-[0_2px_8px_rgba(19,38,27,0.16)]';

  const ringStroke = isLime ? '#13261B' : '#F7FAF4';
  const vectorStroke = isLime ? '#13261B' : '#D6EE8A';

  return (
    <span
      className={cn('inline-grid shrink-0 place-items-center rounded-[28%]', containerStyle, className)}
      style={{ width: size, height: size }}
      aria-hidden="true"
    >
      <svg
        width={Math.round(size * 0.72)}
        height={Math.round(size * 0.72)}
        viewBox="12 12 40 40"
        fill="none"
      >
        <path
          d="M44.5 34.5C43.3 41.1 37.6 46 30.8 46C23.1 46 17 39.7 17 32C17 24.3 23.1 18 30.8 18C35.8 18 40.2 20.6 42.6 24.6"
          stroke={ringStroke}
          strokeWidth="4.4"
          strokeLinecap="round"
        />
        <path
          d="M31.5 35.5L46.5 20.5"
          stroke={vectorStroke}
          strokeWidth="4.6"
          strokeLinecap="round"
        />
        <path
          d="M38.5 20.5H46.5V28.5"
          stroke={vectorStroke}
          strokeWidth="4.2"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        <circle cx="31.5" cy="35.5" r="3" fill={vectorStroke} />
      </svg>
    </span>
  );
}
