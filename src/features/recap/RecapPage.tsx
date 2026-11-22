import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { BookOpen, ChevronDown, ScrollText } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { ChapterStepper } from '../../components/ChapterStepper';
import { useDelayedLoading } from '../../hooks/useDelayedLoading';
import { useSummaries } from '../../hooks/useSummaries';
import { useI18n } from '../../i18n/I18nContext';
import { formatChapterNumber } from '../../services/chapters';
import { cleanChapterTitle } from '../../services/formatting';
import { M3_SPRINGS } from '../../theme/m3Tokens';
import type { ChapterSummaryItem } from '../../types/knowledge';
import { useBookContext } from '../book/BookLayout';

const PAGE_SIZE = 30;

export function RecapPage() {
  const { t } = useI18n();
  const { bookId, bookDetails, activeChapter, prevChapter, nextChapter, goToChapter } =
    useBookContext();

  const recap = useSummaries(bookId, activeChapter?.id ?? null);
  const showSkeleton = useDelayedLoading(recap.loading && !recap.summaries);
  const [expandedIds, setExpandedIds] = useState<Record<string, boolean>>({});
  const [openVolumes, setOpenVolumes] = useState<Record<number, boolean>>({});
  const [openVolRecaps, setOpenVolRecaps] = useState<Record<number, boolean>>({});
  const [visibleLimits, setVisibleLimits] = useState<Record<number, number>>({});

  const toggleSynopsis = (chapterId: string) => {
    setExpandedIds((prev) => ({ ...prev, [chapterId]: !prev[chapterId] }));
  };

  const visibleChapters = useMemo(
    () => (recap.summaries?.chapters ?? []).filter((ch) => !ch.is_service),
    [recap.summaries],
  );

  const volumeRecapMap = useMemo(() => {
    const map = new Map<number, string>();
    for (const v of recap.summaries?.volumes ?? []) {
      map.set(v.volume_num, v.summary);
    }
    return map;
  }, [recap.summaries]);

  // Unified volume list (latest volume on top), combining volume recaps + chapter summaries
  const volumeGroups = useMemo(() => {
    const map = new Map<number, ChapterSummaryItem[]>();
    for (const ch of visibleChapters) {
      const vol = ch.volume_num || 1;
      const list = map.get(vol);
      if (list) list.push(ch);
      else map.set(vol, [ch]);
    }
    for (const volNum of volumeRecapMap.keys()) {
      if (!map.has(volNum)) map.set(volNum, []);
    }
    return Array.from(map.entries())
      .sort(([a], [b]) => b - a)
      .map(([volumeNum, list]) => ({
        volumeNum,
        volumeSummary: volumeRecapMap.get(volumeNum) ?? null,
        chapters: [...list].reverse(),
      }));
  }, [visibleChapters, volumeRecapMap]);

  const encodedBook = encodeURIComponent(bookId);
  const latestVolumeNum = volumeGroups[0]?.volumeNum ?? null;

  const jumpToVolume = (volNum: number) => {
    setOpenVolumes((prev) => ({ ...prev, [volNum]: true }));
    window.requestAnimationFrame(() => {
      document.getElementById(`recap-vol-${volNum}`)?.scrollIntoView({
        behavior: 'smooth',
        block: 'start',
      });
    });
  };

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <div className="flex flex-col gap-3 rounded-[28px] border border-outline-variant/50 bg-surface-container p-4 sm:flex-row sm:items-center sm:justify-between sm:p-5">
        <h1 className="text-2xl font-extrabold tracking-tight">{t.tabs.recap}</h1>

        <ChapterStepper
          chapters={bookDetails.chapters}
          activeChapter={activeChapter}
          hasPrev={Boolean(prevChapter)}
          hasNext={Boolean(nextChapter)}
          onSelectChapter={goToChapter}
          onPrev={() => prevChapter && goToChapter(prevChapter.id)}
          onNext={() => nextChapter && goToChapter(nextChapter.id)}
        />
      </div>

      {showSkeleton ? (
        <div className="space-y-3">
          {[1, 2, 3].map((n) => (
            <div key={n} className="h-28 animate-pulse rounded-[28px] bg-surface-container" />
          ))}
        </div>
      ) : recap.loading && !recap.summaries ? (
        <div className="min-h-[40vh]" />
      ) : recap.error ? (
        <div className="rounded-[28px] bg-error-container p-6 text-sm font-medium text-on-error-container">
          {t.recap.error} ({recap.error})
        </div>
      ) : volumeGroups.length === 0 ? (
        <div className="space-y-2 rounded-[28px] bg-surface-container-low p-10 text-center">
          <ScrollText className="mx-auto h-8 w-8 text-on-surface-variant/60" />
          <p className="text-sm text-on-surface-variant">{t.recap.empty}</p>
        </div>
      ) : (
        <div className="space-y-4">
          {/* Compact quick-jump strip when there are many volumes */}
          {volumeGroups.length > 3 && (
            <div className="flex items-center gap-1.5 overflow-x-auto pb-1">
              {volumeGroups.map((g) => (
                <motion.button
                  key={g.volumeNum}
                  type="button"
                  whileTap={{ scale: 0.94, borderRadius: '10px' }}
                  transition={M3_SPRINGS.spatial.fast}
                  onClick={() => jumpToVolume(g.volumeNum)}
                  className={`m3-color-transition inline-flex h-8 shrink-0 cursor-pointer items-center gap-1.5 rounded-full px-3 text-xs font-bold ${
                    g.volumeSummary
                      ? 'bg-primary-container text-on-primary-container'
                      : 'bg-surface-container-high text-on-surface-variant hover:bg-surface-container-highest'
                  }`}
                >
                  <span>
                    {t.bookmark.volumeShort} {g.volumeNum}
                  </span>
                </motion.button>
              ))}
            </div>
          )}

          {volumeGroups.map((group) => {
            const isOpen =
              openVolumes[group.volumeNum] !== undefined
                ? openVolumes[group.volumeNum]
                : group.volumeNum === latestVolumeNum;
            const isRecapOpen = openVolRecaps[group.volumeNum] !== false;
            const limit = visibleLimits[group.volumeNum] ?? PAGE_SIZE;
            const shownChapters = group.chapters.slice(0, limit);
            const remaining = group.chapters.length - shownChapters.length;

            return (
              <section
                key={group.volumeNum}
                id={`recap-vol-${group.volumeNum}`}
                className="scroll-mt-24 border-b border-outline-variant/40 pb-4 last:border-b-0"
              >
                <motion.button
                  type="button"
                  whileTap={{ scale: 0.99 }}
                  transition={M3_SPRINGS.spatial.fast}
                  onClick={() =>
                    setOpenVolumes((prev) => ({ ...prev, [group.volumeNum]: !isOpen }))
                  }
                  className="m3-color-transition flex w-full cursor-pointer items-center justify-between gap-2 rounded-2xl px-2 py-2.5 text-left hover:bg-surface-container-low"
                >
                  <span className="flex flex-wrap items-center gap-2.5 text-lg font-extrabold tracking-tight text-on-surface">
                    <span>
                      {t.bookmark.volumeFull} {group.volumeNum}
                    </span>
                    <span className="rounded-full bg-surface-container-high px-2.5 py-0.5 font-mono text-xs font-semibold text-on-surface-variant">
                      {group.chapters.length}
                    </span>
                    {group.volumeSummary && (
                      <span className="inline-flex items-center gap-1 rounded-full bg-primary-container px-2.5 py-0.5 text-[11px] font-bold text-on-primary-container">
                        <BookOpen className="h-3 w-3" />
                        <span>{t.recap.completedVolume}</span>
                      </span>
                    )}
                  </span>

                  <motion.span
                    animate={{ rotate: isOpen ? 180 : 0 }}
                    transition={M3_SPRINGS.spatial.fast}
                    className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-surface-container-high text-on-surface-variant"
                  >
                    <ChevronDown className="h-4 w-4" />
                  </motion.span>
                </motion.button>

                <AnimatePresence initial={false}>
                  {isOpen && (
                    <motion.div
                      initial={{ height: 0, opacity: 0 }}
                      animate={{ height: 'auto', opacity: 1 }}
                      exit={{ height: 0, opacity: 0 }}
                      transition={{
                        height: M3_SPRINGS.spatial.default,
                        opacity: M3_SPRINGS.effects.default,
                      }}
                      className="overflow-hidden"
                    >
                      <div className="mt-3 space-y-3">
                        {/* Embedded Volume Recap inside its own volume */}
                        {group.volumeSummary && (
                          <div className="rounded-[22px] border border-primary/25 bg-primary-container/45 p-4 sm:p-5">
                            <button
                              type="button"
                              onClick={() =>
                                setOpenVolRecaps((prev) => ({
                                  ...prev,
                                  [group.volumeNum]: !isRecapOpen,
                                }))
                              }
                              className="flex w-full cursor-pointer items-center justify-between gap-2 text-left"
                            >
                              <span className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider text-primary">
                                <BookOpen className="h-4 w-4" />
                                <span>{t.recap.volumesTitle}</span>
                              </span>
                              <motion.span
                                animate={{ rotate: isRecapOpen ? 180 : 0 }}
                                transition={M3_SPRINGS.spatial.fast}
                                className="text-on-surface-variant"
                              >
                                <ChevronDown className="h-4 w-4" />
                              </motion.span>
                            </button>

                            <AnimatePresence initial={false}>
                              {isRecapOpen && (
                                <motion.div
                                  initial={{ height: 0, opacity: 0 }}
                                  animate={{ height: 'auto', opacity: 1 }}
                                  exit={{ height: 0, opacity: 0 }}
                                  transition={{
                                    height: M3_SPRINGS.spatial.default,
                                    opacity: M3_SPRINGS.effects.default,
                                  }}
                                  className="overflow-hidden"
                                >
                                  <p className="mt-2.5 text-sm leading-relaxed text-on-surface whitespace-pre-line">
                                    {group.volumeSummary}
                                  </p>
                                </motion.div>
                              )}
                            </AnimatePresence>
                          </div>
                        )}

                        {shownChapters.map((chapter) => {
                          const isExpanded = Boolean(expandedIds[chapter.chapter_id]);
                          const cleanTitle = cleanChapterTitle(chapter.title);
                          return (
                            <article
                              key={chapter.chapter_id}
                              className="space-y-2.5 rounded-[22px] border border-outline-variant/40 bg-surface-container-lowest p-4 sm:p-5"
                            >
                              <div className="flex flex-wrap items-baseline justify-between gap-2">
                                <Link
                                  to={`/books/${encodedBook}/read/${encodeURIComponent(chapter.chapter_id)}`}
                                  className="text-[15px] font-bold text-on-surface hover:text-primary"
                                >
                                  <span className="mr-2 font-mono text-primary">
                                    {t.bookmark.volumeShort} {chapter.volume_num} ·{' '}
                                    {t.bookmark.chapterShort}{' '}
                                    {formatChapterNumber(chapter.chapter_num)}
                                  </span>
                                  {cleanTitle}
                                </Link>
                              </div>

                              {chapter.teaser ? (
                                <p className="text-sm leading-relaxed text-on-surface-variant">
                                  {chapter.teaser}
                                </p>
                              ) : (
                                <p className="text-xs italic text-on-surface-variant/70">
                                  {t.recap.noSummaryYet}
                                </p>
                              )}

                              {chapter.synopsis && (
                                <div className="pt-1">
                                  <motion.button
                                    type="button"
                                    whileTap={{ scale: 0.95, borderRadius: '10px' }}
                                    transition={M3_SPRINGS.spatial.fast}
                                    onClick={() => toggleSynopsis(chapter.chapter_id)}
                                    className="m3-color-transition inline-flex h-9 cursor-pointer items-center gap-1.5 rounded-full bg-surface-container-high px-3.5 text-xs font-bold text-on-surface hover:bg-secondary-container hover:text-on-secondary-container"
                                  >
                                    <span>
                                      {isExpanded ? t.recap.hideSynopsis : t.recap.showSynopsis}
                                    </span>
                                    <motion.span
                                      animate={{ rotate: isExpanded ? 180 : 0 }}
                                      transition={M3_SPRINGS.spatial.fast}
                                    >
                                      <ChevronDown className="h-3.5 w-3.5" />
                                    </motion.span>
                                  </motion.button>

                                  <AnimatePresence initial={false}>
                                    {isExpanded && (
                                      <motion.div
                                        initial={{ height: 0, opacity: 0 }}
                                        animate={{ height: 'auto', opacity: 1 }}
                                        exit={{ height: 0, opacity: 0 }}
                                        transition={{
                                          height: M3_SPRINGS.spatial.default,
                                          opacity: M3_SPRINGS.effects.default,
                                        }}
                                        className="overflow-hidden"
                                      >
                                        <div className="mt-3 border-t border-outline-variant/40 pt-3 text-sm leading-relaxed text-on-surface whitespace-pre-line">
                                          {chapter.synopsis}
                                        </div>
                                      </motion.div>
                                    )}
                                  </AnimatePresence>
                                </div>
                              )}
                            </article>
                          );
                        })}

                        {remaining > 0 && (
                          <div className="pt-1 text-center">
                            <motion.button
                              type="button"
                              whileTap={{ scale: 0.96 }}
                              transition={M3_SPRINGS.spatial.fast}
                              onClick={() =>
                                setVisibleLimits((prev) => ({
                                  ...prev,
                                  [group.volumeNum]: limit + PAGE_SIZE,
                                }))
                              }
                              className="m3-color-transition inline-flex h-10 cursor-pointer items-center gap-2 rounded-full bg-surface-container-high px-5 text-xs font-bold text-on-surface hover:bg-primary-container hover:text-on-primary-container"
                            >
                              <span>{t.codex.showMore}</span>
                              <span className="font-mono opacity-75">
                                ({t.codex.remaining}: {remaining})
                              </span>
                            </motion.button>
                          </div>
                        )}
                      </div>
                    </motion.div>
                  )}
                </AnimatePresence>
              </section>
            );
          })}
        </div>
      )}
    </div>
  );
}
