import { useEffect, useRef, useState } from 'react';
import { Languages, Moon, Palette, Sun } from 'lucide-react';
import { AnimatePresence, LayoutGroup, motion } from 'motion/react';
import { useM3Theme } from '../hooks/useTheme';
import { useI18n } from '../i18n/I18nContext';
import { M3_SPRINGS, THEME_SEEDS } from '../theme/m3Tokens';

export function ThemeControls() {
  const { locale, t, toggleLocale } = useI18n();
  const { isDark, seedId, toggleDark, setSeedId } = useM3Theme();
  const [paletteOpen, setPaletteOpen] = useState(false);
  const paletteRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!paletteOpen) return;
    const handleOutside = (e: MouseEvent) => {
      if (!paletteRef.current?.contains(e.target as Node)) setPaletteOpen(false);
    };
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setPaletteOpen(false);
    };
    document.addEventListener('mousedown', handleOutside);
    document.addEventListener('keydown', handleKey);
    return () => {
      document.removeEventListener('mousedown', handleOutside);
      document.removeEventListener('keydown', handleKey);
    };
  }, [paletteOpen]);

  return (
    <div className="flex shrink-0 items-center gap-1.5 sm:gap-2">
      {/* Desktop inline Dynamic Color seed switcher */}
      <LayoutGroup id="theme-seeds">
        <div
          className="hidden md:flex items-center gap-1 rounded-full bg-surface-container-high p-1"
          role="group"
          aria-label={t.theme.paletteLabel}
        >
          <Palette className="ml-1.5 h-3.5 w-3.5 text-on-surface-variant" />
          {THEME_SEEDS.map((seed) => {
            const selected = seed.id === seedId;
            return (
              <motion.button
                key={seed.id}
                type="button"
                onClick={() => setSeedId(seed.id)}
                title={seed.name}
                aria-label={seed.name}
                aria-pressed={selected}
                animate={{
                  borderRadius: selected ? '8px' : '9999px',
                  scale: selected ? 1.08 : 1,
                }}
                whileHover={{ scale: 1.15 }}
                whileTap={{ scale: 0.9, borderRadius: '6px' }}
                transition={M3_SPRINGS.spatial.fast}
                className="relative flex h-5 w-5 cursor-pointer items-center justify-center"
                style={{ backgroundColor: seed.hex }}
              >
                {selected && (
                  <motion.span
                    layoutId="active-seed-dot"
                    className="h-1.5 w-1.5 rounded-full bg-white"
                    transition={M3_SPRINGS.spatial.fast}
                  />
                )}
              </motion.button>
            );
          })}
        </div>
      </LayoutGroup>

      {/* Mobile compact palette button */}
      <div ref={paletteRef} className="relative md:hidden">
        <motion.button
          type="button"
          onClick={() => setPaletteOpen((v) => !v)}
          aria-label={t.theme.paletteLabel}
          whileTap={{ scale: 0.9, borderRadius: '10px' }}
          transition={M3_SPRINGS.spatial.fast}
          className="m3-color-transition flex h-9 w-9 cursor-pointer items-center justify-center rounded-full bg-surface-container-high text-primary"
        >
          <Palette className="h-4 w-4" />
        </motion.button>

        <AnimatePresence>
          {paletteOpen && (
            <motion.div
              initial={{ opacity: 0, y: -6, scale: 0.95 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: -6, scale: 0.95 }}
              transition={M3_SPRINGS.spatial.fast}
              className="absolute right-0 top-full mt-2 flex items-center gap-2 rounded-full border border-outline-variant/60 bg-surface-container-high p-2 shadow-lg z-50"
            >
              {THEME_SEEDS.map((seed) => {
                const selected = seed.id === seedId;
                return (
                  <motion.button
                    key={seed.id}
                    type="button"
                    onClick={() => {
                      setSeedId(seed.id);
                      setPaletteOpen(false);
                    }}
                    aria-label={seed.name}
                    animate={{ borderRadius: selected ? '8px' : '9999px' }}
                    whileTap={{ scale: 0.88 }}
                    className="flex h-7 w-7 cursor-pointer items-center justify-center"
                    style={{ backgroundColor: seed.hex }}
                  >
                    {selected && <span className="h-2 w-2 rounded-full bg-white" />}
                  </motion.button>
                );
              })}
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      <motion.button
        type="button"
        onClick={toggleLocale}
        aria-label={t.language.switchLabel}
        title={t.language.switchLabel}
        whileHover={{ scale: 1.04 }}
        whileTap={{ scale: 0.92, borderRadius: '10px' }}
        transition={M3_SPRINGS.spatial.fast}
        className="m3-color-transition inline-flex h-9 items-center gap-1 rounded-full bg-surface-container-high px-2.5 text-xs font-bold text-on-surface hover:bg-primary-container hover:text-on-primary-container cursor-pointer"
      >
        <Languages className="h-3.5 w-3.5" />
        <span>{locale.toUpperCase()}</span>
      </motion.button>

      <motion.button
        type="button"
        onClick={toggleDark}
        aria-label={isDark ? t.theme.toLight : t.theme.toDark}
        title={isDark ? t.theme.toLight : t.theme.toDark}
        whileHover={{ scale: 1.05 }}
        whileTap={{ scale: 0.9, borderRadius: '10px' }}
        transition={M3_SPRINGS.spatial.fast}
        className="m3-color-transition flex h-9 w-9 items-center justify-center rounded-full bg-surface-container-high text-on-surface hover:bg-primary-container hover:text-on-primary-container cursor-pointer"
      >
        {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
      </motion.button>
    </div>
  );
}
