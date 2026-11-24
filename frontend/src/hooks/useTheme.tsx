import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { applyM3Theme, THEME_SEEDS } from '../theme/m3Tokens';

const THEME_STORAGE_KEY = 'bw_theme';
const SEED_STORAGE_KEY = 'bw_theme_seed';

interface ThemeContextValue {
  isDark: boolean;
  seedId: string;
  toggleDark: () => void;
  setSeedId: (id: string) => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

function readInitialDark(): boolean {
  try {
    const saved = localStorage.getItem(THEME_STORAGE_KEY);
    if (saved === 'dark') return true;
    if (saved === 'light') return false;
  } catch {
    // Ignore storage errors
  }
  return window.matchMedia('(prefers-color-scheme: dark)').matches;
}

function readInitialSeed(): string {
  try {
    const saved = localStorage.getItem(SEED_STORAGE_KEY);
    if (saved && THEME_SEEDS.some((s) => s.id === saved)) {
      return saved;
    }
  } catch {
    // Ignore storage errors
  }
  return THEME_SEEDS[0].id;
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [isDark, setIsDark] = useState<boolean>(readInitialDark);
  const [seedId, setSeedIdState] = useState<string>(readInitialSeed);

  useEffect(() => {
    const seed = THEME_SEEDS.find((s) => s.id === seedId) ?? THEME_SEEDS[0];
    applyM3Theme(seed.hex, isDark);
    try {
      localStorage.setItem(THEME_STORAGE_KEY, isDark ? 'dark' : 'light');
      localStorage.setItem(SEED_STORAGE_KEY, seed.id);
    } catch {
      // Ignore storage errors
    }
  }, [isDark, seedId]);

  const toggleDark = useCallback(() => {
    setIsDark((prev) => !prev);
  }, []);

  const setSeedId = useCallback((id: string) => {
    setSeedIdState(id);
  }, []);

  return (
    <ThemeContext.Provider value={{ isDark, seedId, toggleDark, setSeedId }}>
      {children}
    </ThemeContext.Provider>
  );
}

export function useM3Theme(): ThemeContextValue {
  const ctx = useContext(ThemeContext);
  if (!ctx) {
    throw new Error('useM3Theme must be used inside ThemeProvider');
  }
  return ctx;
}
