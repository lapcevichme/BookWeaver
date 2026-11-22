import {
  argbFromHex,
  Hct,
  hexFromArgb,
  MaterialDynamicColors,
  SchemeExpressive,
} from '@material/material-color-utilities';

export interface ThemeSeed {
  id: string;
  name: string;
  hex: string;
}

export const THEME_SEEDS: ThemeSeed[] = [
  { id: 'jade', name: 'Apothecary Jade', hex: '#1F7A5C' },
  { id: 'indigo', name: 'Indigo Ink', hex: '#6750A4' },
  { id: 'terracotta', name: 'Warm Terracotta', hex: '#C85A32' },
  { id: 'cobalt', name: 'Cobalt', hex: '#2563EB' },
];

/**
 * Exact M3 Expressive motion tokens converted for Motion (mass = 1).
 * Spatial springs bounce; effects curves never overshoot.
 */
export const M3_SPRINGS = {
  spatial: {
    fast: { type: 'spring' as const, stiffness: 800, damping: 34, mass: 1 },
    default: { type: 'spring' as const, stiffness: 380, damping: 31, mass: 1 },
    slow: { type: 'spring' as const, stiffness: 200, damping: 23, mass: 1 },
  },
  effects: {
    fast: { duration: 0.15, ease: [0.31, 0.94, 0.34, 1.0] as const },
    default: { duration: 0.2, ease: [0.34, 0.8, 0.34, 1.0] as const },
    slow: { duration: 0.3, ease: [0.34, 0.88, 0.34, 1.0] as const },
  },
};

const COLOR_TOKEN_MAP = {
  primary: MaterialDynamicColors.primary,
  'on-primary': MaterialDynamicColors.onPrimary,
  'primary-container': MaterialDynamicColors.primaryContainer,
  'on-primary-container': MaterialDynamicColors.onPrimaryContainer,
  secondary: MaterialDynamicColors.secondary,
  'on-secondary': MaterialDynamicColors.onSecondary,
  'secondary-container': MaterialDynamicColors.secondaryContainer,
  'on-secondary-container': MaterialDynamicColors.onSecondaryContainer,
  tertiary: MaterialDynamicColors.tertiary,
  'on-tertiary': MaterialDynamicColors.onTertiary,
  'tertiary-container': MaterialDynamicColors.tertiaryContainer,
  'on-tertiary-container': MaterialDynamicColors.onTertiaryContainer,
  error: MaterialDynamicColors.error,
  'on-error': MaterialDynamicColors.onError,
  'error-container': MaterialDynamicColors.errorContainer,
  'on-error-container': MaterialDynamicColors.onErrorContainer,
  surface: MaterialDynamicColors.surface,
  'surface-dim': MaterialDynamicColors.surfaceDim,
  'surface-bright': MaterialDynamicColors.surfaceBright,
  'surface-container-lowest': MaterialDynamicColors.surfaceContainerLowest,
  'surface-container-low': MaterialDynamicColors.surfaceContainerLow,
  'surface-container': MaterialDynamicColors.surfaceContainer,
  'surface-container-high': MaterialDynamicColors.surfaceContainerHigh,
  'surface-container-highest': MaterialDynamicColors.surfaceContainerHighest,
  'on-surface': MaterialDynamicColors.onSurface,
  'on-surface-variant': MaterialDynamicColors.onSurfaceVariant,
  outline: MaterialDynamicColors.outline,
  'outline-variant': MaterialDynamicColors.outlineVariant,
  'inverse-surface': MaterialDynamicColors.inverseSurface,
  'inverse-on-surface': MaterialDynamicColors.inverseOnSurface,
  'inverse-primary': MaterialDynamicColors.inversePrimary,
} as const;

export function applyM3Theme(seedHex: string, isDark: boolean): void {
  const hct = Hct.fromInt(argbFromHex(seedHex));
  const scheme = new SchemeExpressive(hct, isDark, 0.0);
  const root = document.documentElement;

  for (const [tokenName, dynamicColor] of Object.entries(COLOR_TOKEN_MAP)) {
    const argb = dynamicColor.getArgb(scheme);
    root.style.setProperty(`--md-sys-color-${tokenName}`, hexFromArgb(argb));
  }

  root.classList.toggle('dark', isDark);
  root.style.colorScheme = isDark ? 'dark' : 'light';
}
