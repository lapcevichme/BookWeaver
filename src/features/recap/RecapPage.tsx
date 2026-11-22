import { useState } from 'react';
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
import { useBookContext } from '../book/BookLayout';

export function RecapPage() {
  const { t } = useI18n();
  const { bookId, bookDetails, activeChapter, prevChapter, nextChapter, goToChapter } =
    useBookContext();

  const recap = useSummaries(bookId, activeChapter?.id ?? null);
  const showSkeleton = useDelayedLoading(recap.loading && !recap.summaries);
  const [expandedIds, setExpandedIds] = useState<Record<string, boolean>>({});

  const toggleSynopsis = (chapterId: string) => {
    setExpandedIds((prev) => ({ ...prev, [chapterId]: !prev[chapterId] }));
  };

  const visibleChapters = (recap.summaries?.chapters ?? []).filter((ch) => !ch.is_service);
  const volumes = recap.summaries?.volumes ?? [];
  const encodedBook = encodeURIComponent(bookId);

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
      ) : visibleChapters.length === 0 && volumes.length === 0 ? (
        <div className="space-y-2 rounded-[28px] bg-surface-container-low p-10 text-center">
          <ScrollText className="mx-auto h-8 w-8 text-on-surface-variant/60" />
          <p className="text-sm text-on-surface-variant">{t.recap.empty}</p>
        </div>
      ) : (
        <div className="space-y-8">
          {volumes.length > 0 && (
            <section className="space-y-3">
              <h2 className="flex items-center gap-2 text-lg font-bold">
                <BookOpen className="h-5 w-5 text-primary" />
                <span>{t.recap.volumesTitle}</span>
              </h2>
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                {volumes.map((vol) => (
                  <article
                    key={vol.volume_num}
                    className="space-y-2.5 rounded-[28px] border border-primary/25 bg-primary-container/45 p-5"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-base font-bold">
                        {t.bookmark.volumeFull} {vol.volume_num}
                      </span>
                      <span className="rounded-full bg-primary px-2.5 py-0.5 text-[11px] font-bold text-on-primary">
                        {t.recap.completedVolume}
                      </span>
                    </div>
                    <p className="text-sm leading-relaxed whitespace-pre-line">{vol.summary}</p>
                  </article>
                ))}
              </div>
            </section>
          )}

          <section className="space-y-3">
            <h2 className="flex items-center gap-2 text-lg font-bold">
              <ScrollText className="h-5 w-5 text-primary" />
              <span>{t.recap.chaptersTitle}</span>
            </h2>

            <div className="space-y-3">
              {[...visibleChapters].reverse().map((chapter) => {
                const isExpanded = Boolean(expandedIds[chapter.chapter_id]);
                const cleanTitle = cleanChapterTitle(chapter.title);
                return (
                  <article
                    key={chapter.chapter_id}
                    className="space-y-2.5 rounded-[24px] border border-outline-variant/40 bg-surface-container-lowest p-4 sm:p-5"
                  >
                    <div className="flex flex-wrap items-baseline justify-between gap-2">
                      <Link
                        to={`/books/${encodedBook}/read/${encodeURIComponent(chapter.chapter_id)}`}
                        className="text-[15px] font-bold text-on-surface hover:text-primary"
                      >
                        <span className="mr-2 font-mono text-primary">
                          {t.bookmark.volumeShort} {chapter.volume_num} · {t.bookmark.chapterShort}{' '}
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
                          <span>{isExpanded ? t.recap.hideSynopsis : t.recap.showSynopsis}</span>
                          <ChevronDown
                            className={`h-3.5 w-3.5 transition-transform duration-200 ${isExpanded ? 'rotate-180' : ''}`}
                          />
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
            </div>
          </section>
        </div>
      )}
    </div>
  );
}
