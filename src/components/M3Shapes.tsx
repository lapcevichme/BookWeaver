import type { ReactNode } from 'react';
import { motion } from 'motion/react';
import { M3_SPRINGS } from '../theme/m3Tokens';

export type M3ShapeVariant = 'softBurst' | 'cookie9' | 'clover4' | 'flower' | 'slanted' | 'gem';

export const M3_SHAPE_VARIANTS: M3ShapeVariant[] = [
  'cookie9',
  'clover4',
  'softBurst',
  'flower',
  'gem',
  'slanted',
];

function hashString(input: string): number {
  let hash = 0;
  for (let i = 0; i < input.length; i++) {
    hash = (hash * 31 + input.charCodeAt(i)) | 0;
  }
  return Math.abs(hash);
}

export function pickShapeForKey(key: string): M3ShapeVariant {
  return M3_SHAPE_VARIANTS[hashString(key) % M3_SHAPE_VARIANTS.length];
}

const TONE_CLASSES = [
  'text-primary-container [&_.m3-shape-fg]:text-on-primary-container',
  'text-secondary-container [&_.m3-shape-fg]:text-on-secondary-container',
  'text-tertiary-container [&_.m3-shape-fg]:text-on-tertiary-container',
] as const;

export function pickToneForKey(key: string): string {
  return TONE_CLASSES[hashString(`${key}_tone`) % TONE_CLASSES.length];
}

function getPolygonPoints(sides: number, outerR: number, innerR: number, offsetDeg = -90): string {
  const pts: string[] = [];
  const total = sides * 2;
  for (let i = 0; i < total; i++) {
    const r = i % 2 === 0 ? outerR : innerR;
    const angle = ((offsetDeg + (i * 360) / total) * Math.PI) / 180;
    pts.push(`${(50 + r * Math.cos(angle)).toFixed(2)},${(50 + r * Math.sin(angle)).toFixed(2)}`);
  }
  return pts.join(' ');
}

function getScallopedPath(lobes: number, baseR: number, amp: number, steps = 90): string {
  const cmds: string[] = [];
  for (let i = 0; i < steps; i++) {
    const theta = (i / steps) * Math.PI * 2 - Math.PI / 2;
    const r = baseR + amp * Math.cos(lobes * (theta + Math.PI / 2));
    const x = 50 + r * Math.cos(theta);
    const y = 50 + r * Math.sin(theta);
    cmds.push(`${i === 0 ? 'M' : 'L'}${x.toFixed(2)} ${y.toFixed(2)}`);
  }
  return `${cmds.join(' ')} Z`;
}

function ShapeSvg({ variant }: { variant: M3ShapeVariant }) {
  switch (variant) {
    case 'softBurst':
      return (
        <svg viewBox="0 0 100 100" className="h-full w-full fill-current" aria-hidden="true">
          <polygon points={getPolygonPoints(10, 48, 37)} strokeLinejoin="round" />
        </svg>
      );
    case 'cookie9':
      return (
        <svg viewBox="0 0 100 100" className="h-full w-full fill-current" aria-hidden="true">
          <path d={getScallopedPath(9, 43, 5.2)} />
        </svg>
      );
    case 'clover4':
      return (
        <svg viewBox="0 0 100 100" className="h-full w-full fill-current" aria-hidden="true">
          <circle cx="34" cy="34" r="24" />
          <circle cx="66" cy="34" r="24" />
          <circle cx="34" cy="66" r="24" />
          <circle cx="66" cy="66" r="24" />
          <rect x="26" y="26" width="48" height="48" rx="12" />
        </svg>
      );
    case 'flower':
      return (
        <svg viewBox="0 0 100 100" className="h-full w-full fill-current" aria-hidden="true">
          <circle cx="50" cy="24" r="20" />
          <circle cx="76" cy="50" r="20" />
          <circle cx="50" cy="76" r="20" />
          <circle cx="24" cy="50" r="20" />
          <circle cx="32" cy="32" r="19" />
          <circle cx="68" cy="32" r="19" />
          <circle cx="32" cy="68" r="19" />
          <circle cx="68" cy="68" r="19" />
          <circle cx="50" cy="50" r="26" />
        </svg>
      );
    case 'slanted':
      return (
        <svg viewBox="0 0 100 100" className="h-full w-full fill-current" aria-hidden="true">
          <path d="M22 12 L86 12 C93 12 96 18 94 25 L80 81 C78 86 73 88 67 88 L14 88 C7 88 4 82 6 75 L20 19 C22 14 27 12 22 12 Z" />
        </svg>
      );
    case 'gem':
      return (
        <svg viewBox="0 0 100 100" className="h-full w-full fill-current" aria-hidden="true">
          <path d="M35 8 L65 8 C70 8 74 10 77 14 L92 34 C95 38 95 43 92 47 L56 90 C53 94 47 94 44 90 L8 47 C5 43 5 38 8 34 L23 14 C26 10 30 8 35 8 Z" />
        </svg>
      );
  }
}

interface M3ShapeBadgeProps {
  variant: M3ShapeVariant;
  className?: string;
  contentClassName?: string;
  children?: ReactNode;
  spinOnHover?: boolean;
  interactive?: boolean;
}

export function M3ShapeBadge({
  variant,
  className = '',
  contentClassName = '',
  children,
  spinOnHover = true,
  interactive = true,
}: M3ShapeBadgeProps) {
  const hasExplicitPosition = /\b(absolute|fixed|sticky)\b/.test(className);
  const positionClass = hasExplicitPosition ? '' : 'relative';

  return (
    <motion.div
      className={`${positionClass} inline-flex items-center justify-center select-none ${className}`}
      whileHover={
        interactive
          ? spinOnHover
            ? { rotate: 16, scale: 1.07 }
            : { scale: 1.05 }
          : undefined
      }
      whileTap={interactive ? { scale: 0.93 } : undefined}
      transition={M3_SPRINGS.spatial.fast}
    >
      <div aria-hidden="true" className="pointer-events-none absolute inset-0">
        <ShapeSvg variant={variant} />
      </div>
      {children ? (
        <div className={`m3-shape-fg relative z-10 flex items-center justify-center ${contentClassName}`}>
          {children}
        </div>
      ) : null}
    </motion.div>
  );
}
