import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Link,
  Outlet,
  useLocation,
  useNavigate,
  useOutletContext,
  useParams,
  useSearchParams,
} from 'react-router-dom';
import { AlertCircle, ArrowLeft, BookOpen, RefreshCw, ScrollText, Users } from 'lucide-react';
import { LayoutGroup, motion } from 'motion/react';
import { ThemeControls } from '../../components/ThemeControls';
import { useDelayedLoading } from '../../hooks/useDelayedLoading';
import { useI18n } from '../../i18n/I18nContext';
import { fetchBookDetails, getCachedBookDetails } from '../../services/api';
import { readSavedBookmark, saveBookmark } from '../../services/bookmarks';
import { getAdjacentChapter, resolveActiveChapter } from '../../services/chapters';
import { M3_SPRINGS } from '../../theme/m3Tokens';
import type { BookDetails, ChapterInfo } from '../../types/book';

export interface BookOutletContext {
  bookId: string;
  bookDetails: BookDetails;
  activeChapter: ChapterInfo | null;
  prevChapter: ChapterInfo | null;
  nextChapter: ChapterInfo | null;
  goToChapter: (chapterId: string) => void;
}

export function useBookContext(): BookOutletContext {
  return useOutletContext<BookOutletContext>();
}

export function BookLayout() {
  const { t } = useI18n();
  const { bookId = '', chapterId: routeChapterId } = useParams();
  const [searchParams] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();

  const cachedDetails = bookId ? getCachedBookDetails(bookId) : null;
  const [bookDetails, setBookDetails] = useState<BookDetails | null>(cachedDetails);
  const [loading, setLoading] = useState(!cachedDetails);
  const [error, setError] = useState<string | null>(null);
  const [reloadTick, setReloadTick] = useState(0);
  const showSkeleton = useDelayedLoading(loading && !bookDetails);

  useEffect(() => {
    if (!bookId) return;
    const forceRefresh = reloadTick > 0;
    const cached = getCachedBookDetails(bookId);
    if (cached && !forceRefresh) {
      setBookDetails(cached);
      setLoading(false);
      setError(null);
      return;
    }

    const controller = new AbortController();
    setLoading(true);
    setError(null);

    fetchBookDetails(bookId, controller.signal, forceRefresh)
      .then((details) => setBookDetails(details))
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return;
        setError(err instanceof Error ? err.message : 'Unknown error');
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });

    return () => controller.abort();
  }, [bookId, reloadTick]);

  const chapters = bookDetails?.chapters ?? [];
  const requestedChapterId =
    routeChapterId || searchParams.get('as_of') || readSavedBookmark(bookId);

  const activeChapter = useMemo(
    () => resolveActiveChapter(chapters, requestedChapterId),
    [chapters, requestedChapterId],
  );

  useEffect(() => {
    if (bookId && activeChapter) {
      saveBookmark(bookId, activeChapter.id);
    }
  }, [bookId, activeChapter]);

  const prevChapter = getAdjacentChapter(chapters, activeChapter?.id ?? null, -1);
  const nextChapter = getAdjacentChapter(chapters, activeChapter?.id ?? null, 1);

  const section: 'read' | 'characters' | 'recap' = location.pathname.includes('/characters')
    ? 'characters'
    : location.pathname.includes('/recap')
      ? 'recap'
      : 'read';

  const encodedBook = encodeURIComponent(bookId);
  const asOfQuery = activeChapter ? `?as_of=${encodeURIComponent(activeChapter.id)}` : '';

  const readHref = activeChapter
    ? `/books/${encodedBook}/read/${encodeURIComponent(activeChapter.id)}`
    : `/books/${encodedBook}`;
  const charactersHref = `/books/${encodedBook}/characters${asOfQuery}`;
  const recapHref = `/books/${encodedBook}/recap${asOfQuery}`;

  const goToChapter = useCallback(
    (nextChapterId: string) => {
      saveBookmark(bookId, nextChapterId);
      if (section === 'read') {
        navigate(`/books/${encodedBook}/read/${encodeURIComponent(nextChapterId)}`);
      } else {
        const nextParams = new URLSearchParams(searchParams);
        nextParams.set('as_of', nextChapterId);
        navigate(`${location.pathname}?${nextParams.toString()}`);
      }
    },
    [bookId, encodedBook, location.pathname, navigate, searchParams, section],
  );

  const navItems = [
    { id: 'read' as const, label: t.tabs.read, to: readHref, icon: BookOpen },
    { id: 'characters' as const, label: t.tabs.codex, to: charactersHref, icon: Users },
    { id: 'recap' as const, label: t.tabs.recap, to: recapHref, icon: ScrollText },
  ];

  return (
    <div className="min-h-screen bg-surface text-on-surface flex flex-col">
      {/* Floating Expressive Capsule TopBar with layoutRoot so sticky layoutId pills never jump on scroll */}
      <motion.header
        layoutRoot
        className="sticky top-0 z-40 w-full bg-gradient-to-b from-surface via-surface/95 to-transparent pt-3 pb-1"
      >
        <div className="mx-auto w-full max-w-6xl px-3 sm:px-6">
          <motion.nav
            initial={{ y: -14, opacity: 0 }}
            animate={{ y: 0, opacity: 1 }}
            transition={{ y: M3_SPRINGS.spatial.default, opacity: M3_SPRINGS.effects.default }}
            className="m3-color-transition flex items-center justify-between gap-2 rounded-full border border-outline-variant/60 bg-surface-container/95 px-2 py-2 shadow-sm backdrop-blur-md sm:px-3"
          >
            <div className="flex min-w-0 items-center gap-1.5 sm:gap-2">
              <motion.div whileTap={{ scale: 0.92 }} transition={M3_SPRINGS.spatial.fast}>
                <Link
                  to="/"
                  title={t.app.backToLibrary}
                  aria-label={t.app.backToLibrary}
                  className="m3-color-transition flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-surface-container-high text-on-surface hover:bg-primary-container hover:text-on-primary-container"
                >
                  <ArrowLeft className="h-4 w-4" />
                </Link>
              </motion.div>
              <span className="truncate text-sm font-bold tracking-tight max-w-[130px] sm:max-w-[200px]">
                {bookDetails?.title || bookId}
              </span>
            </div>

            {/* Desktop Center Nav Pills with isolated LayoutGroup */}
            <LayoutGroup id="book-desktop-nav">
              <div className="hidden sm:flex items-center gap-1 rounded-full bg-surface-container-high/80 p-1">
                {navItems.map((item) => {
                  const active = section === item.id;
                  const Icon = item.icon;
                  return (
                    <Link
                      key={item.id}
                      to={item.to}
                      className={`relative inline-flex h-9 items-center gap-1.5 rounded-full px-4 text-xs font-bold transition-colors ${
                        active
                          ? 'text-on-primary'
                          : 'text-on-surface-variant hover:text-on-surface'
                      }`}
                    >
                      {active && (
                        <motion.span
                          layoutId="desktop-nav-pill"
                          transition={M3_SPRINGS.spatial.fast}
                          className="absolute inset-0 rounded-full bg-primary"
                        />
                      )}
                      <Icon className="relative z-10 h-3.5 w-3.5" />
                      <span className="relative z-10">{item.label}</span>
                    </Link>
                  );
                })}
              </div>
            </LayoutGroup>

            <ThemeControls />
          </motion.nav>
        </div>
      </motion.header>

      <main className="mx-auto flex-1 w-full max-w-6xl px-4 py-6 pb-28 sm:px-6 sm:py-8 sm:pb-12">
        {error ? (
          <div className="flex flex-col items-start justify-between gap-4 rounded-[28px] bg-error-container p-6 text-on-error-container sm:flex-row sm:items-center">
            <div className="flex items-start gap-3">
              <AlertCircle className="mt-0.5 h-5 w-5 shrink-0" />
              <div className="space-y-1">
                <h2 className="text-base font-bold">{t.books.errorTitle}</h2>
                <p className="text-sm opacity-90">{error}</p>
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
          <div className="mx-auto max-w-3xl space-y-4">
            <div className="h-12 w-2/3 animate-pulse rounded-2xl bg-surface-container" />
            <div className="h-64 animate-pulse rounded-[28px] bg-surface-container" />
          </div>
        ) : !bookDetails ? (
          <div className="min-h-[50vh]" />
        ) : (
          <Outlet
            context={
              {
                bookId,
                bookDetails,
                activeChapter,
                prevChapter,
                nextChapter,
                goToChapter,
              } satisfies BookOutletContext
            }
          />
        )}
      </main>

      {/* Mobile M3 Flexible Bottom Navigation Bar (3 destinations) */}
      <nav
        aria-label="Mobile navigation"
        className="sm:hidden fixed bottom-0 inset-x-0 z-40 border-t border-outline-variant/50 bg-surface-container/95 backdrop-blur-md pb-[env(safe-area-inset-bottom)]"
      >
        <div className="mx-auto grid h-16 max-w-md grid-cols-3">
          {navItems.map((item) => {
            const active = section === item.id;
            const Icon = item.icon;
            return (
              <Link
                key={item.id}
                to={item.to}
                className="flex flex-col items-center justify-center gap-1"
              >
                <span className="relative flex h-8 w-16 items-center justify-center">
                  {active && (
                    <motion.span
                      layoutId="mobile-nav-pill"
                      transition={M3_SPRINGS.spatial.fast}
                      className="absolute inset-0 rounded-full bg-secondary-container"
                    />
                  )}
                  <Icon
                    className={`relative z-10 h-5 w-5 ${
                      active ? 'text-on-secondary-container' : 'text-on-surface-variant'
                    }`}
                  />
                </span>
                <span
                  className={`text-[11px] ${
                    active ? 'font-bold text-secondary' : 'font-medium text-on-surface-variant'
                  }`}
                >
                  {item.label}
                </span>
              </Link>
            );
          })}
        </div>
      </nav>
    </div>
  );
}
