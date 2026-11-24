import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Bookmark,
  Check,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Search,
  X,
} from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { useI18n } from '../i18n/I18nContext';
import {
  buildChapterBuckets,
  filterChaptersByQuery,
  formatChapterNumber,
} from '../services/chapters';
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
  const [query, setQuery] = useState('');
  const [expandedBuckets, setExpandedBuckets] = useState<Record<string, boolean>>({});
  const containerRef = useRef<HTMLDivElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);

  const buckets = useMemo(() => buildChapterBuckets(chapters, 100), [chapters]);
  const isLargeBook = chapters.length > 80;

  const activeBucketKey = useMemo(() => {
    if (!activeChapter) return buckets[0]?.key ?? null;
    const found = buckets.find((b) => b.chapters.some((c) => c.id === activeChapter.id));
    return found?.key ?? buckets[0]?.key ?? null;
  }, [buckets, activeChapter]);

  const searchResults = useMemo(() => {
    if (!query.trim()) return [];
    return filterChaptersByQuery(chapters, query, 60);
  }, [chapters, query]);

  useEffect(() => {
    if (!open) return;
    setQuery('');
    if (activeBucketKey) {
      setExpandedBuckets({ [activeBucketKey]: true });
    }
    const focusTimer = window.setTimeout(() => {
      searchInputRef.current?.focus();
    }, 50);

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
      window.clearTimeout(focusTimer);
      document.removeEventListener('mousedown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [open, activeBucketKey]);

  if (chapters.length === 0) return null;

  const popoverAlignClass =
    align === 'left'
      ? 'left-0 right-0 sm:right-auto'
      : align === 'center'
        ? 'left-0 right-0 sm:left-1/2 sm:-translate-x-1/2 sm:right-auto'
        : 'left-0 right-0 sm:left-auto sm:right-0';

  const activeCleanTitle = cleanChapterTitle(activeChapter?.title);

  const selectAndClose = (chapterId: string) => {
    onSelectChapter(chapterId);
    setOpen(false);
    setQuery('');
  };

  const renderChapterRow = (chapter: ChapterInfo, showVolume = false) => {
    const isCurrent = chapter.id === activeChapter?.id;
    const cleanTitle = cleanChapterTitle(chapter.title);
    return (
      <li key={chapter.id}>
        <button
          type="button"
          onClick={() => selectAndClose(chapter.id)}
          className={`m3-color-transition flex min-h-11 w-full cursor-pointer items-center justify-between gap-2 rounded-2xl px-3 py-2 text-left text-sm ${
            isCurrent
              ? 'bg-primary-container font-bold text-on-primary-container'
              : 'text-on-surface hover:bg-surface-container-highest'
          }`}
        >
          <span className="truncate">
            <span className="mr-1.5 font-mono text-xs font-semibold">
              {showVolume ? `${t.bookmark.volumeShort} ${chapter.volume_num} · ` : ''}
              {t.bookmark.chapterShort} {formatChapterNumber(chapter.chapter_num)}
            </span>
            {cleanTitle ? `— ${cleanTitle}` : `(${chapter.id})`}
          </span>
          {isCurrent && <Check className="h-4 w-4 shrink-0" />}
        </button>
      </li>
    );
  };

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
            {/* Quick chapter jump / search input */}
            <div className="relative mb-2">
              <Search className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-on-surface-variant" />
              <input
                ref={searchInputRef}
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && searchResults.length > 0) {
                    e.preventDefault();
                    selectAndClose(searchResults[0].id);
                  }
                }}
                placeholder={t.bookmark.searchChapterPlaceholder}
                className="h-10 w-full rounded-full border border-outline-variant/60 bg-surface-container-lowest pl-10 pr-9 text-sm text-on-surface placeholder:text-on-surface-variant/75 focus:border-primary focus:outline-none"
              />
              {query && (
                <button
                  type="button"
                  onClick={() => setQuery('')}
                  className="absolute right-1.5 top-1/2 flex h-7 w-7 -translate-y-1/2 cursor-pointer items-center justify-center rounded-full text-on-surface-variant hover:bg-surface-container-highest"
                >
                  <X className="h-3.5 w-3.5" />
                </button>
              )}
            </div>

            <div className="max-h-80 space-y-2 overflow-y-auto pr-1">
              {query.trim() ? (
                searchResults.length > 0 ? (
                  <ul className="space-y-0.5">
                    {searchResults.map((ch) => renderChapterRow(ch, true))}
                  </ul>
                ) : (
                  <p className="py-6 text-center text-xs text-on-surface-variant">
                    {t.bookmark.noChaptersFound}
                  </p>
                )
              ) : (
                buckets.map((bucket) => {
                  const isOpen = isLargeBook
                    ? Boolean(expandedBuckets[bucket.key])
                    : expandedBuckets[bucket.key] !== false;
                  const hasActive = bucket.key === activeBucketKey;
                  const heading = bucket.rangeLabel
                    ? `${t.bookmark.volumeFull} ${bucket.volumeNum} · ${bucket.rangeLabel}`
                    : `${t.bookmark.volumeFull} ${bucket.volumeNum}`;

                  return (
                    <div key={bucket.key}>
                      <button
                        type="button"
                        onClick={() =>
                          setExpandedBuckets((prev) => ({ ...prev, [bucket.key]: !isOpen }))
                        }
                        className={`m3-color-transition flex w-full cursor-pointer items-center justify-between rounded-xl px-2.5 py-2 text-left text-xs font-bold uppercase tracking-wider ${
                          hasActive
                            ? 'bg-secondary-container/60 text-on-secondary-container'
                            : 'text-on-surface-variant hover:bg-surface-container-highest'
                        }`}
                      >
                        <span>
                          {heading}{' '}
                          <span className="font-mono font-normal opacity-75">
                            ({bucket.chapters.length})
                          </span>
                        </span>
                        <ChevronDown
                          className={`h-3.5 w-3.5 transition-transform duration-200 ${isOpen ? 'rotate-180' : ''}`}
                        />
                      </button>

                      {isOpen && (
                        <ul className="mt-1 space-y-0.5">
                          {bucket.chapters.map((ch) => renderChapterRow(ch, false))}
                        </ul>
                      )}
                    </div>
                  );
                })
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
