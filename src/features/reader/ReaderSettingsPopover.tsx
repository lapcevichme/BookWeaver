import { useEffect, useRef, useState } from 'react';
import { Minus, Plus, Type } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { useI18n } from '../../i18n/I18nContext';
import { M3_SPRINGS } from '../../theme/m3Tokens';

export type ReaderLineHeight = 'tight' | 'normal' | 'relaxed';
export type ReaderColumnWidth = 'narrow' | 'standard' | 'wide';
export type ReaderFontFamily = 'sans' | 'serif';

export interface ReaderPrefs {
  fontSize: number;
  lineHeight: ReaderLineHeight;
  columnWidth: ReaderColumnWidth;
  fontFamily: ReaderFontFamily;
}

const PREFS_KEY = 'bw_reader_prefs';

const DEFAULT_PREFS: ReaderPrefs = {
  fontSize: 17,
  lineHeight: 'normal',
  columnWidth: 'standard',
  fontFamily: 'sans',
};

export function readReaderPrefs(): ReaderPrefs {
  try {
    const raw = localStorage.getItem(PREFS_KEY);
    if (!raw) return DEFAULT_PREFS;
    const parsed = JSON.parse(raw) as Partial<ReaderPrefs>;
    return {
      fontSize:
        typeof parsed.fontSize === 'number' && parsed.fontSize >= 14 && parsed.fontSize <= 24
          ? parsed.fontSize
          : DEFAULT_PREFS.fontSize,
      lineHeight:
        parsed.lineHeight === 'tight' ||
        parsed.lineHeight === 'normal' ||
        parsed.lineHeight === 'relaxed'
          ? parsed.lineHeight
          : DEFAULT_PREFS.lineHeight,
      columnWidth:
        parsed.columnWidth === 'narrow' ||
        parsed.columnWidth === 'standard' ||
        parsed.columnWidth === 'wide'
          ? parsed.columnWidth
          : DEFAULT_PREFS.columnWidth,
      fontFamily:
        parsed.fontFamily === 'sans' || parsed.fontFamily === 'serif'
          ? parsed.fontFamily
          : DEFAULT_PREFS.fontFamily,
    };
  } catch {
    return DEFAULT_PREFS;
  }
}

export function saveReaderPrefs(prefs: ReaderPrefs): void {
  try {
    localStorage.setItem(PREFS_KEY, JSON.stringify(prefs));
  } catch {
    // Ignore storage errors
  }
}

export const LINE_HEIGHT_VALUE: Record<ReaderLineHeight, string> = {
  tight: '1.6',
  normal: '1.82',
  relaxed: '2.05',
};

export const COLUMN_WIDTH_CLASS: Record<ReaderColumnWidth, string> = {
  narrow: 'max-w-[56ch]',
  standard: 'max-w-[68ch]',
  wide: 'max-w-[82ch]',
};

interface ReaderSettingsPopoverProps {
  prefs: ReaderPrefs;
  onChange: (next: ReaderPrefs) => void;
}

export function ReaderSettingsPopover({ prefs, onChange }: ReaderSettingsPopoverProps) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handleOutside = (e: MouseEvent) => {
      if (!containerRef.current?.contains(e.target as Node)) setOpen(false);
    };
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', handleOutside);
    document.addEventListener('keydown', handleKey);
    return () => {
      document.removeEventListener('mousedown', handleOutside);
      document.removeEventListener('keydown', handleKey);
    };
  }, [open]);

  const update = <K extends keyof ReaderPrefs>(key: K, value: ReaderPrefs[K]) => {
    onChange({ ...prefs, [key]: value });
  };

  return (
    <div ref={containerRef} className="relative shrink-0">
      <motion.button
        type="button"
        whileTap={{ scale: 0.92, borderRadius: '12px' }}
        transition={M3_SPRINGS.spatial.fast}
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        aria-label={t.reader.settingsTitle}
        title={t.reader.settingsTitle}
        className={`m3-color-transition inline-flex h-11 cursor-pointer items-center gap-1.5 rounded-full px-3 text-xs font-bold ${
          open
            ? 'bg-primary text-on-primary'
            : 'bg-surface-container-high text-on-surface hover:bg-surface-container-highest'
        }`}
      >
        <Type className="h-4 w-4" />
        <span className="hidden sm:inline font-mono">{prefs.fontSize}</span>
      </motion.button>

      <AnimatePresence>
        {open && (
          <motion.div
            initial={{ opacity: 0, y: -8, scale: 0.96 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -6, scale: 0.96 }}
            transition={{
              y: M3_SPRINGS.spatial.fast,
              scale: M3_SPRINGS.spatial.fast,
              opacity: M3_SPRINGS.effects.fast,
            }}
            className="absolute right-0 top-full z-50 mt-2 w-72 rounded-[24px] border border-outline-variant/70 bg-surface-container-high p-4 shadow-xl space-y-4"
          >
            {/* Font size */}
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs font-bold text-on-surface-variant">{t.reader.fontSize}</span>
              <div className="inline-flex items-center gap-1 rounded-full bg-surface-container-lowest p-1">
                <motion.button
                  type="button"
                  whileTap={{ scale: 0.9 }}
                  onClick={() => update('fontSize', Math.max(14, prefs.fontSize - 1))}
                  aria-label={t.reader.fontSmaller}
                  className="flex h-8 w-8 cursor-pointer items-center justify-center rounded-full text-on-surface hover:bg-surface-container-high"
                >
                  <Minus className="h-3.5 w-3.5" />
                </motion.button>
                <span className="w-8 text-center font-mono text-xs font-bold text-on-surface">
                  {prefs.fontSize}
                </span>
                <motion.button
                  type="button"
                  whileTap={{ scale: 0.9 }}
                  onClick={() => update('fontSize', Math.min(24, prefs.fontSize + 1))}
                  aria-label={t.reader.fontLarger}
                  className="flex h-8 w-8 cursor-pointer items-center justify-center rounded-full text-on-surface hover:bg-surface-container-high"
                >
                  <Plus className="h-3.5 w-3.5" />
                </motion.button>
              </div>
            </div>

            {/* Line height */}
            <div className="space-y-1.5">
              <span className="block text-xs font-bold text-on-surface-variant">
                {t.reader.lineHeight}
              </span>
              <div className="grid grid-cols-3 gap-1 rounded-full bg-surface-container-lowest p-1">
                {(
                  [
                    ['tight', t.reader.lineTight],
                    ['normal', t.reader.lineNormal],
                    ['relaxed', t.reader.lineRelaxed],
                  ] as const
                ).map(([val, label]) => (
                  <button
                    key={val}
                    type="button"
                    onClick={() => update('lineHeight', val)}
                    className={`m3-color-transition h-8 cursor-pointer rounded-full text-xs font-bold ${
                      prefs.lineHeight === val
                        ? 'bg-primary text-on-primary'
                        : 'text-on-surface-variant hover:text-on-surface'
                    }`}
                  >
                    {label}
                  </button>
                ))}
              </div>
            </div>

            {/* Column width */}
            <div className="hidden sm:block space-y-1.5">
              <span className="block text-xs font-bold text-on-surface-variant">
                {t.reader.columnWidth}
              </span>
              <div className="grid grid-cols-3 gap-1 rounded-full bg-surface-container-lowest p-1">
                {(
                  [
                    ['narrow', t.reader.widthNarrow],
                    ['standard', t.reader.widthStandard],
                    ['wide', t.reader.widthWide],
                  ] as const
                ).map(([val, label]) => (
                  <button
                    key={val}
                    type="button"
                    onClick={() => update('columnWidth', val)}
                    className={`m3-color-transition h-8 cursor-pointer rounded-full text-xs font-bold ${
                      prefs.columnWidth === val
                        ? 'bg-primary text-on-primary'
                        : 'text-on-surface-variant hover:text-on-surface'
                    }`}
                  >
                    {label}
                  </button>
                ))}
              </div>
            </div>

            {/* Prose font family */}
            <div className="space-y-1.5">
              <span className="block text-xs font-bold text-on-surface-variant">
                {t.reader.fontFamily}
              </span>
              <div className="grid grid-cols-2 gap-1 rounded-full bg-surface-container-lowest p-1">
                {(
                  [
                    ['sans', t.reader.fontSans],
                    ['serif', t.reader.fontSerif],
                  ] as const
                ).map(([val, label]) => (
                  <button
                    key={val}
                    type="button"
                    onClick={() => update('fontFamily', val)}
                    className={`m3-color-transition h-8 cursor-pointer rounded-full text-xs font-bold ${
                      prefs.fontFamily === val
                        ? 'bg-primary text-on-primary'
                        : 'text-on-surface-variant hover:text-on-surface'
                    }`}
                    style={
                      val === 'serif' ? { fontFamily: 'Georgia, "Times New Roman", serif' } : undefined
                    }
                  >
                    {label}
                  </button>
                ))}
              </div>
            </div>

            <div className="hidden sm:block pt-1 text-center font-mono text-[10px] text-on-surface-variant/80">
              {t.reader.keyboardHint}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
