import { useEffect, useMemo, useRef, useState } from 'react';
import { Bookmark, Check, ChevronDown, ChevronLeft, ChevronRight } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { useI18n } from '../i18n/I18nContext';
import { formatChapterNumber, groupChaptersByVolume } from '../services/chapters';
import { cleanChapterTitle } from '../services/formatting';
import { M3_SPRINGS } from '../theme/m3Tokens';
import type { ChapterInfo } from '../types/book';

interface ChapterStepperProps {
  chapters: ChapterInfo[];
  activeChapter: ChapterInfo | null;
  hasPrev: boolean;
  hasNext: boolean;
  onSelectChapter: (chapterId: string) => void;
  onPrev: () => void;
  onNext: () => void;
  align?: 'left' | 'right' | 'center';
}

export function ChapterStepper({
  chapters,
  activeChapter,
  hasPrev,
  hasNext,
  onSelectChapter,
  onPrev,
  onNext,
  align = 'right',
}: ChapterStepperProps) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const volumes = useMemo(() => groupChaptersByVolume(chapters), [chapters]);

  useEffect(() => {
    if (!open) return;
    const handlePointerDown = (event: MouseEvent) => {
      if (!containerRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [open]);

  if (chapters.length === 0) return null;

  const popoverAlignClass =
    align === 'left'
      ? 'left-0 right-0 sm:right-auto'
      : align === 'center'
        ? 'left-0 right-0 sm:left-1/2 sm:-translate-x-1/2 sm:right-auto'
        : 'left-0 right-0 sm:left-auto sm:right-0';

  const activeCleanTitle = cleanChapterTitle(activeChapter?.title);

  return (
    <div ref={containerRef} className="relative w-full sm:w-auto">
      {/* Fixed-width Connected Button Group so arrows never shift */}
      <div className="flex w-full items-center gap-1 sm:w-[344px]">
        <motion.button
          type="button"
          onClick={onPrev}
          disabled={!hasPrev}
          aria-label={t.bookmark.prevChapter}
          title={t.bookmark.prevChapter}
          whileTap={hasPrev ? { scale: 0.92, borderRadius: '14px' } : undefined}
          transition={M3_SPRINGS.spatial.fast}
          className="m3-color-transition inline-flex h-12 w-12 shrink-0 cursor-pointer items-center justify-center rounded-l-full rounded-r-lg bg-surface-container-highest text-on-surface hover:bg-secondary-container hover:text-on-secondary-container disabled:pointer-events-none disabled:opacity-35"
        >
          <ChevronLeft className="h-5 w-5" />
        </motion.button>

        <motion.button
          type="button"
          onClick={() => setOpen((prev) => !prev)}
          aria-expanded={open}
          whileTap={{ scale: 0.98 }}
          transition={M3_SPRINGS.spatial.fast}
          className="m3-color-transition flex h-12 min-w-0 flex-1 cursor-pointer items-center justify-between gap-2 rounded-lg border border-outline-variant/60 bg-surface-container-lowest px-3.5 text-on-surface hover:bg-surface-container-high"
        >
          <span className="flex min-w-0 items-center gap-2">
            <Bookmark className="h-4 w-4 shrink-0 text-primary" />
            <span className="min-w-0 text-left leading-tight">
              <span className="block truncate text-xs font-bold text-primary tabular-nums">
                {activeChapter
                  ? `${t.bookmark.volumeShort} ${activeChapter.volume_num} · ${t.bookmark.chapterShort} ${formatChapterNumber(activeChapter.chapter_num)}`
                  : t.bookmark.pickChapter}
              </span>
              <span className="block truncate text-xs text-on-surface-variant">
                {activeCleanTitle || '\u00A0'}
              </span>
            </span>
          </span>
          <ChevronDown
            className={`h-4 w-4 shrink-0 text-on-surface-variant transition-transform duration-200 ${open ? 'rotate-180' : ''}`}
          />
        </motion.button>

        <motion.button
          type="button"
          onClick={onNext}
          disabled={!hasNext}
          aria-label={t.bookmark.nextChapter}
          title={t.bookmark.nextChapter}
          whileTap={hasNext ? { scale: 0.92, borderRadius: '14px' } : undefined}
          transition={M3_SPRINGS.spatial.fast}
          className="m3-color-transition inline-flex h-12 w-12 shrink-0 cursor-pointer items-center justify-center rounded-r-full rounded-l-lg bg-primary text-on-primary hover:opacity-95 disabled:pointer-events-none disabled:opacity-35"
        >
          <ChevronRight className="h-5 w-5" />
        </motion.button>
      </div>

      <AnimatePresence>
        {open && (
          <motion.div
            initial={{ opacity: 0, y: -8, scale: 0.97 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -6, scale: 0.97 }}
            transition={{
              y: M3_SPRINGS.spatial.fast,
              scale: M3_SPRINGS.spatial.fast,
              opacity: M3_SPRINGS.effects.fast,
            }}
            className={`absolute top-full z-50 mt-2 rounded-[24px] border border-outline-variant/70 bg-surface-container-high p-2.5 shadow-xl sm:w-96 ${popoverAlignClass}`}
          >
            <div className="max-h-80 space-y-3 overflow-y-auto pr-1">
              {volumes.map((group) => (
                <div key={group.volumeNum}>
                  <div className="sticky top-0 bg-surface-container-high/95 px-2.5 py-1 text-[11px] font-bold uppercase tracking-wider text-on-surface-variant backdrop-blur-xs">
                    {t.bookmark.volumeFull} {group.volumeNum}
                  </div>
                  <ul className="mt-1 space-y-0.5">
                    {group.chapters.map((chapter) => {
                      const isCurrent = chapter.id === activeChapter?.id;
                      const cleanTitle = cleanChapterTitle(chapter.title);
                      return (
                        <li key={chapter.id}>
                          <button
                            type="button"
                            onClick={() => {
                              onSelectChapter(chapter.id);
                              setOpen(false);
                            }}
                            className={`m3-color-transition flex min-h-11 w-full cursor-pointer items-center justify-between gap-2 rounded-2xl px-3 py-2 text-left text-sm ${
                              isCurrent
                                ? 'bg-primary-container font-bold text-on-primary-container'
                                : 'text-on-surface hover:bg-surface-container-highest'
                            }`}
                          >
                            <span className="truncate">
                              <span className="mr-1.5 font-mono text-xs font-semibold">
                                {t.bookmark.chapterShort} {formatChapterNumber(chapter.chapter_num)}
                              </span>
                              {cleanTitle ? `— ${cleanTitle}` : `(${chapter.id})`}
                            </span>
                            {isCurrent && <Check className="h-4 w-4 shrink-0" />}
                          </button>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              ))}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
