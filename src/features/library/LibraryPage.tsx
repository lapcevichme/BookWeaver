import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  AlertCircle,
  ArrowUpRight,
  BookMarked,
  BookOpen,
  RefreshCw,
  Search,
  Sparkles,
  X,
} from 'lucide-react';
import { motion } from 'motion/react';
import { M3ShapeBadge, pickShapeForKey, pickToneForKey } from '../../components/M3Shapes';
import { ThemeControls } from '../../components/ThemeControls';
import { useDelayedLoading } from '../../hooks/useDelayedLoading';
import { useI18n } from '../../i18n/I18nContext';
import { fetchProjectsCatalog, getBookCoverUrl, getCachedProjectsCatalog } from '../../services/api';
import { readSavedBookmark } from '../../services/bookmarks';
import { formatChapterCount, formatSavedBookmarkLabel } from '../../services/formatting';
import { M3_SPRINGS } from '../../theme/m3Tokens';
import type { ProjectCatalogItem } from '../../types/book';

function sortCatalog(list: ProjectCatalogItem[]): ProjectCatalogItem[] {
  return [...list].sort((a, b) => {
    if (a.id === 'kusuriya_v1_test') return -1;
    if (b.id === 'kusuriya_v1_test') return 1;
    const aSaved = Boolean(readSavedBookmark(a.id));
    const bSaved = Boolean(readSavedBookmark(b.id));
    if (aSaved !== bSaved) return aSaved ? -1 : 1;
    return a.title.localeCompare(b.title);
  });
}

function BookShelfCard({ book, index }: { book: ProjectCatalogItem; index: number }) {
  const { locale, t } = useI18n();
  const [coverFailed, setCoverFailed] = useState(false);
  const savedChapter = readSavedBookmark(book.id);
  const targetUrl = savedChapter
    ? `/books/${encodeURIComponent(book.id)}/read/${encodeURIComponent(savedChapter)}`
    : `/books/${encodeURIComponent(book.id)}`;

  const shape = pickShapeForKey(book.id);
  const tone = pickToneForKey(book.id);
  const initial = book.title.trim().charAt(0).toUpperCase() || '?';

  return (
    <motion.div
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{
        y: { ...M3_SPRINGS.spatial.default, delay: Math.min(index * 0.02, 0.16) },
        opacity: { ...M3_SPRINGS.effects.default, delay: Math.min(index * 0.02, 0.16) },
      }}
      whileHover={{ y: -3 }}
      whileTap={{ scale: 0.98 }}
      className="h-full"
    >
      <Link
        to={targetUrl}
        className="m3-color-transition group flex h-full flex-col justify-between gap-4 rounded-[28px] border border-outline-variant/50 bg-surface-container-lowest p-4 hover:border-primary/40 hover:bg-surface-container-low"
      >
        <div className="flex items-start gap-3.5">
          {book.has_cover && !coverFailed ? (
            <div className="h-16 w-12 shrink-0 overflow-hidden rounded-2xl bg-primary-container">
              <img
                src={getBookCoverUrl(book.id)}
                alt={book.title}
                className="h-full w-full object-cover"
                onError={() => setCoverFailed(true)}
              />
            </div>
          ) : (
            <M3ShapeBadge
              variant={shape}
              className={`h-14 w-14 shrink-0 ${tone}`}
              contentClassName="text-lg font-extrabold"
            >
              {initial}
            </M3ShapeBadge>
          )}

          <div className="min-w-0 flex-1 space-y-1">
            <h2 className="line-clamp-2 text-base font-extrabold leading-snug text-on-surface group-hover:text-primary">
              {book.title}
            </h2>
            <p className="truncate text-xs text-on-surface-variant">
              {book.author || t.books.unknownAuthor}
              {book.chapters_count > 0 && ` · ${formatChapterCount(book.chapters_count, locale)}`}
            </p>
            {book.title !== book.id && (
              <p className="truncate font-mono text-[10px] text-on-surface-variant/70">{book.id}</p>
            )}
          </div>
        </div>

        <div className="flex items-center justify-between gap-2 pt-1">
          {savedChapter ? (
            <span className="inline-flex items-center gap-1 rounded-full bg-secondary-container px-2.5 py-1 font-mono text-[11px] font-bold text-on-secondary-container">
              {formatSavedBookmarkLabel(savedChapter, t.bookmark.volumeShort, t.bookmark.chapterShort)}
            </span>
          ) : (
            <span className="text-xs font-bold text-primary">{t.books.startReading}</span>
          )}
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-surface-container-high text-on-surface transition-transform duration-200 group-hover:translate-x-0.5 group-hover:-translate-y-0.5 group-hover:bg-primary group-hover:text-on-primary">
            <ArrowUpRight className="h-4 w-4" />
          </span>
        </div>
      </Link>
    </motion.div>
  );
}

export function LibraryPage() {
  const { locale, t } = useI18n();
  const cachedCatalog = getCachedProjectsCatalog();
  const [books, setBooks] = useState<ProjectCatalogItem[]>(() =>
    cachedCatalog ? sortCatalog(cachedCatalog) : [],
  );
  const [loading, setLoading] = useState(!cachedCatalog);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');
  const [reloadTick, setReloadTick] = useState(0);
  const showSkeleton = useDelayedLoading(loading && books.length === 0);

  useEffect(() => {
    const controller = new AbortController();
    const forceRefresh = reloadTick > 0;
    if (!getCachedProjectsCatalog() || forceRefresh) {
      setLoading(true);
    }
    setError(null);

    fetchProjectsCatalog(controller.signal, forceRefresh)
      .then((list) => {
        setBooks(sortCatalog(list));
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return;
        setError(err instanceof Error ? err.message : 'Unknown error');
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });

    return () => controller.abort();
  }, [reloadTick]);

  const featuredBook = useMemo(() => {
    if (query.trim()) return null;
    return books.find((b) => Boolean(readSavedBookmark(b.id))) ?? books[0] ?? null;
  }, [books, query]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    const list = !q
      ? books
      : books.filter(
          (b) =>
            b.title.toLowerCase().includes(q) ||
            b.id.toLowerCase().includes(q) ||
            (b.author ?? '').toLowerCase().includes(q),
        );
    return featuredBook ? list.filter((b) => b.id !== featuredBook.id) : list;
  }, [books, query, featuredBook]);

  const featuredSaved = featuredBook ? readSavedBookmark(featuredBook.id) : null;
  const featuredHref = featuredBook
    ? featuredSaved
      ? `/books/${encodeURIComponent(featuredBook.id)}/read/${encodeURIComponent(featuredSaved)}`
      : `/books/${encodeURIComponent(featuredBook.id)}`
    : '/';

  return (
    <div className="min-h-screen bg-surface text-on-surface">
      <motion.header
        layoutRoot
        className="sticky top-0 z-40 w-full bg-gradient-to-b from-surface via-surface/95 to-transparent pt-3 pb-1"
      >
        <div className="mx-auto w-full max-w-6xl px-3 sm:px-6">
          <motion.nav
            initial={{ y: -14, opacity: 0 }}
            animate={{ y: 0, opacity: 1 }}
            transition={{ y: M3_SPRINGS.spatial.default, opacity: M3_SPRINGS.effects.default }}
            className="m3-color-transition flex items-center justify-between gap-2 rounded-full border border-outline-variant/60 bg-surface-container/95 px-3 py-2 shadow-sm backdrop-blur-md"
          >
            <div className="flex items-center gap-2.5 pl-1">
              <span className="flex h-8 w-8 items-center justify-center rounded-full bg-primary text-on-primary">
                <BookMarked className="h-4 w-4" />
              </span>
              <span className="text-sm font-extrabold tracking-tight">{t.app.name}</span>
            </div>
            <ThemeControls />
          </motion.nav>
        </div>
      </motion.header>

      <main className="mx-auto max-w-6xl space-y-7 px-4 py-6 sm:px-6 sm:py-9">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
          <div className="space-y-1">
            <h1 className="text-3xl font-extrabold tracking-tight sm:text-4xl">
              {t.app.libraryTitle}
            </h1>
            <p className="max-w-xl text-sm text-on-surface-variant">{t.app.librarySubtitle}</p>
          </div>

          <div className="relative w-full sm:w-72">
            <Search className="pointer-events-none absolute left-4 top-1/2 h-4 w-4 -translate-y-1/2 text-on-surface-variant" />
            <input
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={t.books.searchPlaceholder}
              className="m3-color-transition h-12 w-full rounded-full bg-surface-container-high pl-11 pr-10 text-sm text-on-surface placeholder:text-on-surface-variant focus:bg-surface-container-lowest focus:outline-2 focus:outline-primary"
            />
            {query && (
              <button
                type="button"
                onClick={() => setQuery('')}
                className="absolute right-2 top-1/2 flex h-8 w-8 -translate-y-1/2 cursor-pointer items-center justify-center rounded-full text-on-surface-variant hover:bg-surface-container-highest"
              >
                <X className="h-4 w-4" />
              </button>
            )}
          </div>
        </div>

        {error ? (
          <div className="flex flex-col items-start justify-between gap-4 rounded-[28px] bg-error-container p-6 text-on-error-container sm:flex-row sm:items-center">
            <div className="flex items-start gap-3">
              <AlertCircle className="mt-0.5 h-5 w-5 shrink-0" />
              <div className="space-y-1">
                <h2 className="text-base font-bold">{t.books.errorTitle}</h2>
                <p className="text-sm opacity-90">{t.books.errorHint}</p>
              </div>
            </div>
            <motion.button
              type="button"
              whileTap={{ scale: 0.94, borderRadius: '12px' }}
              transition={M3_SPRINGS.spatial.fast}
              onClick={() => setReloadTick((n) => n + 1)}
              className="inline-flex h-11 shrink-0 cursor-pointer items-center gap-2 rounded-full bg-error px-5 text-sm font-bold text-on-error"
            >
              <RefreshCw className="h-4 w-4" />
              <span>{t.books.retry}</span>
            </motion.button>
          </div>
        ) : showSkeleton ? (
          <div className="space-y-6">
            <div className="h-44 animate-pulse rounded-[36px] bg-surface-container" />
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {[1, 2, 3, 4, 5, 6].map((n) => (
                <div key={n} className="h-36 animate-pulse rounded-[28px] bg-surface-container" />
              ))}
            </div>
          </div>
        ) : loading && books.length === 0 ? (
          <div className="min-h-[40vh]" />
        ) : books.length === 0 ? (
          <div className="rounded-[28px] bg-surface-container-low p-10 text-center text-sm text-on-surface-variant">
            {t.books.empty}
          </div>
        ) : (
          <>
            {/* Expressive Hero Spotlight Card */}
            {featuredBook && (
              <motion.section
                initial={{ opacity: 0, y: 14 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ y: M3_SPRINGS.spatial.default, opacity: M3_SPRINGS.effects.default }}
                className="relative overflow-hidden rounded-[36px] bg-primary-container p-6 text-on-primary-container sm:p-8"
              >
                {/* Playful background M3 Expressive shapes */}
                <M3ShapeBadge
                  variant="cookie9"
                  interactive={false}
                  className="pointer-events-none absolute -right-10 -top-10 h-44 w-44 text-primary/15 sm:h-56 sm:w-56"
                />
                <M3ShapeBadge
                  variant="clover4"
                  interactive={false}
                  className="pointer-events-none absolute -bottom-10 right-24 hidden h-32 w-32 text-tertiary/20 sm:block"
                />

                <div className="relative z-10 flex flex-col gap-5 sm:flex-row sm:items-center sm:justify-between">
                  <div className="flex items-start gap-4 min-w-0">
                    <M3ShapeBadge
                      variant={pickShapeForKey(featuredBook.id)}
                      className="h-16 w-16 shrink-0 text-primary [&_.m3-shape-fg]:text-on-primary sm:h-20 sm:w-20"
                      contentClassName="text-2xl font-extrabold"
                    >
                      <BookOpen className="h-7 w-7 sm:h-8 sm:w-8" />
                    </M3ShapeBadge>

                    <div className="min-w-0 space-y-1.5">
                      <div className="inline-flex items-center gap-1.5 rounded-full bg-surface-container-lowest/85 px-3 py-1 text-xs font-bold text-primary">
                        <Sparkles className="h-3.5 w-3.5" />
                        <span>
                          {featuredSaved
                            ? formatSavedBookmarkLabel(
                                featuredSaved,
                                t.bookmark.volumeShort,
                                t.bookmark.chapterShort,
                              )
                            : t.books.startReading}
                        </span>
                      </div>
                      <h2 className="text-2xl font-extrabold tracking-tight sm:text-3xl">
                        {featuredBook.title}
                      </h2>
                      <p className="text-sm opacity-85">
                        {featuredBook.author || t.books.unknownAuthor}
                        {featuredBook.chapters_count > 0 &&
                          ` · ${formatChapterCount(featuredBook.chapters_count, locale)}`}
                      </p>
                    </div>
                  </div>

                  <motion.div
                    whileHover={{ scale: 1.03 }}
                    whileTap={{ scale: 0.95 }}
                    transition={M3_SPRINGS.spatial.fast}
                    className="shrink-0"
                  >
                    <Link
                      to={featuredHref}
                      className="inline-flex h-12 w-full sm:w-auto items-center justify-center gap-2 rounded-full bg-primary px-6 text-sm font-bold text-on-primary shadow-sm"
                    >
                      <span>{featuredSaved ? t.books.continueReading : t.books.startReading}</span>
                      <ArrowUpRight className="h-4 w-4" />
                    </Link>
                  </motion.div>
                </div>
              </motion.section>
            )}

            {filtered.length > 0 && (
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                {filtered.map((book, idx) => (
                  <BookShelfCard key={book.id} book={book} index={idx} />
                ))}
              </div>
            )}
          </>
        )}
      </main>
    </div>
  );
}
