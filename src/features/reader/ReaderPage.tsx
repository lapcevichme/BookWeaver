import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  AlertCircle,
  ArrowLeft,
  ArrowRight,
  ChevronDown,
  Image as ImageIcon,
  Minus,
  Plus,
  Sparkles,
  Type,
  Users,
} from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { ChapterStepper } from '../../components/ChapterStepper';
import { M3ShapeBadge } from '../../components/M3Shapes';
import { useCodex } from '../../hooks/useCodex';
import { useI18n } from '../../i18n/I18nContext';
import { fetchChapterText, getBookImageUrl } from '../../services/api';
import { formatChapterNumber } from '../../services/chapters';
import { cleanChapterTitle, parseChapterBlocks } from '../../services/formatting';
import { M3_SPRINGS } from '../../theme/m3Tokens';
import type { ChapterText } from '../../types/book';
import { useBookContext } from '../book/BookLayout';

const FONT_SIZE_KEY = 'bw_reader_font_size';

function ChapterIllustration({
  bookId,
  filename,
  alt,
}: {
  bookId: string;
  filename: string | null;
  alt: string;
}) {
  const [failed, setFailed] = useState(false);

  if (!filename || failed) {
    return (
      <div className="my-5 flex items-center justify-center gap-2 rounded-2xl border border-dashed border-outline-variant/70 bg-surface-container-low px-4 py-3 text-xs font-semibold text-on-surface-variant">
        <ImageIcon className="h-4 w-4 text-primary" />
        <span>{alt}</span>
      </div>
    );
  }

  return (
    <figure className="my-6 overflow-hidden rounded-[24px] border border-outline-variant/40 bg-surface-container-low">
      <img
        src={getBookImageUrl(bookId, filename)}
        alt={alt}
        loading="lazy"
        onError={() => setFailed(true)}
        className="mx-auto max-h-[640px] w-full object-contain"
      />
    </figure>
  );
}

function readFontSize(): number {
  try {
    const raw = Number(localStorage.getItem(FONT_SIZE_KEY));
    if (raw >= 14 && raw <= 24) return raw;
  } catch {
    // Ignore storage errors
  }
  return 17;
}

export function ReaderPage() {
  const { t } = useI18n();
  const { bookId, bookDetails, activeChapter, prevChapter, nextChapter, goToChapter } =
    useBookContext();

  const [chapterText, setChapterText] = useState<ChapterText | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fontSize, setFontSize] = useState<number>(readFontSize);
  const [fontMenuOpen, setFontMenuOpen] = useState(false);
  const [mobileCastOpen, setMobileCastOpen] = useState(false);
  const fontMenuRef = useRef<HTMLDivElement>(null);

  const codex = useCodex(bookId, activeChapter?.id ?? null, prevChapter?.id ?? null);

  useEffect(() => {
    try {
      localStorage.setItem(FONT_SIZE_KEY, String(fontSize));
    } catch {
      // Ignore storage errors
    }
  }, [fontSize]);

  useEffect(() => {
    if (!fontMenuOpen) return;
    const handleOutside = (e: MouseEvent) => {
      if (!fontMenuRef.current?.contains(e.target as Node)) setFontMenuOpen(false);
    };
    document.addEventListener('mousedown', handleOutside);
    return () => document.removeEventListener('mousedown', handleOutside);
  }, [fontMenuOpen]);

  useEffect(() => {
    if (!bookId || !activeChapter) {
      setChapterText(null);
      return;
    }

    const controller = new AbortController();
    setLoading(true);
    setError(null);
    setMobileCastOpen(false);

    fetchChapterText(bookId, activeChapter.id, controller.signal)
      .then((data) => {
        setChapterText(data);
        window.scrollTo({ top: 0, behavior: 'smooth' });
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return;
        setError(err instanceof Error ? err.message : 'Unknown error');
        setChapterText(null);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });

    return () => controller.abort();
  }, [bookId, activeChapter]);

  const blocks = useMemo(() => {
    if (!chapterText?.text) return [];
    return parseChapterBlocks(chapterText.text, activeChapter?.title);
  }, [chapterText, activeChapter?.title]);

  const featuredCharacters = useMemo(() => {
    if (!activeChapter) return [];
    return codex.characters
      .filter((c) => codex.deltaMap[c.id]?.hasAnyChange)
      .slice(0, 8);
  }, [codex.characters, codex.deltaMap, activeChapter]);

  const encodedBook = encodeURIComponent(bookId);
  const asOfQuery = activeChapter ? `?as_of=${encodeURIComponent(activeChapter.id)}` : '';
  const cleanTitle = cleanChapterTitle(activeChapter?.title);

  return (
    <div className="mx-auto max-w-4xl space-y-4 sm:space-y-5">
      {/* Compact, Single-Row Reader Control Bar */}
      <div className="flex items-center justify-between gap-2 rounded-[28px] border border-outline-variant/50 bg-surface-container p-2.5 sm:px-4 sm:py-3">
        <div className="min-w-0 flex-1 sm:flex-initial">
          <ChapterStepper
            chapters={bookDetails.chapters}
            activeChapter={activeChapter}
            hasPrev={Boolean(prevChapter)}
            hasNext={Boolean(nextChapter)}
            onSelectChapter={goToChapter}
            onPrev={() => prevChapter && goToChapter(prevChapter.id)}
            onNext={() => nextChapter && goToChapter(nextChapter.id)}
            align="left"
          />
        </div>

        {/* Font size popover button (compact on mobile, inline on desktop) */}
        <div ref={fontMenuRef} className="relative shrink-0">
          <div className="hidden sm:inline-flex items-center gap-1 rounded-full bg-surface-container-high p-1">
            <motion.button
              type="button"
              whileTap={{ scale: 0.9 }}
              transition={M3_SPRINGS.spatial.fast}
              onClick={() => setFontSize((s) => Math.max(14, s - 1))}
              aria-label={t.reader.fontSmaller}
              title={t.reader.fontSmaller}
              className="flex h-9 w-9 cursor-pointer items-center justify-center rounded-full text-on-surface hover:bg-surface-container-highest"
            >
              <Minus className="h-3.5 w-3.5" />
            </motion.button>
            <span className="w-8 text-center font-mono text-xs font-bold text-on-surface-variant">
              {fontSize}
            </span>
            <motion.button
              type="button"
              whileTap={{ scale: 0.9 }}
              transition={M3_SPRINGS.spatial.fast}
              onClick={() => setFontSize((s) => Math.min(24, s + 1))}
              aria-label={t.reader.fontLarger}
              title={t.reader.fontLarger}
              className="flex h-9 w-9 cursor-pointer items-center justify-center rounded-full text-on-surface hover:bg-surface-container-highest"
            >
              <Plus className="h-3.5 w-3.5" />
            </motion.button>
          </div>

          <motion.button
            type="button"
            whileTap={{ scale: 0.92, borderRadius: '12px' }}
            transition={M3_SPRINGS.spatial.fast}
            onClick={() => setFontMenuOpen((v) => !v)}
            aria-label="Font size"
            className="sm:hidden flex h-12 w-12 cursor-pointer items-center justify-center rounded-full bg-surface-container-high text-on-surface"
          >
            <Type className="h-4 w-4" />
          </motion.button>

          <AnimatePresence>
            {fontMenuOpen && (
              <motion.div
                initial={{ opacity: 0, y: -6, scale: 0.95 }}
                animate={{ opacity: 1, y: 0, scale: 1 }}
                exit={{ opacity: 0, y: -6, scale: 0.95 }}
                transition={M3_SPRINGS.spatial.fast}
                className="sm:hidden absolute right-0 top-full z-50 mt-2 flex items-center gap-1 rounded-full border border-outline-variant/60 bg-surface-container-high p-1.5 shadow-lg"
              >
                <button
                  type="button"
                  onClick={() => setFontSize((s) => Math.max(14, s - 1))}
                  className="flex h-9 w-9 items-center justify-center rounded-full bg-surface-container-lowest text-on-surface"
                >
                  <Minus className="h-4 w-4" />
                </button>
                <span className="w-9 text-center font-mono text-xs font-bold">{fontSize}</span>
                <button
                  type="button"
                  onClick={() => setFontSize((s) => Math.min(24, s + 1))}
                  className="flex h-9 w-9 items-center justify-center rounded-full bg-surface-container-lowest text-on-surface"
                >
                  <Plus className="h-4 w-4" />
                </button>
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      </div>

      {/* Quick Jump Strip: collapsible on mobile so text starts high above the fold */}
      {featuredCharacters.length > 0 && (
        <div className="rounded-[24px] border border-outline-variant/40 bg-surface-container-low px-4 py-2.5 sm:py-3">
          <button
            type="button"
            onClick={() => setMobileCastOpen((v) => !v)}
            className="flex w-full cursor-pointer items-center justify-between gap-2 sm:hidden"
          >
            <span className="inline-flex items-center gap-1.5 text-xs font-bold text-on-surface">
              <Users className="h-3.5 w-3.5 text-primary" />
              <span>
                {t.reader.inThisChapter} ({featuredCharacters.length})
              </span>
            </span>
            <ChevronDown
              className={`h-4 w-4 text-on-surface-variant transition-transform ${mobileCastOpen ? 'rotate-180' : ''}`}
            />
          </button>

          <div
            className={`${mobileCastOpen ? 'mt-2.5 flex' : 'hidden'} sm:flex flex-wrap items-center gap-2`}
          >
            <span className="hidden sm:inline-flex mr-1 items-center gap-1.5 text-xs font-bold uppercase tracking-wider text-on-surface-variant">
              <Users className="h-3.5 w-3.5 text-primary" />
              <span>{t.reader.inThisChapter}:</span>
            </span>

            {featuredCharacters.map((char) => {
              const isNew = codex.deltaMap[char.id]?.isNew;
              return (
                <motion.div key={char.id} whileHover={{ scale: 1.04 }} whileTap={{ scale: 0.95 }}>
                  <Link
                    to={`/books/${encodedBook}/characters/${encodeURIComponent(char.id)}${asOfQuery}`}
                    className={`m3-color-transition inline-flex h-8 items-center gap-1.5 rounded-full px-3 text-xs font-bold ${
                      isNew
                        ? 'bg-tertiary-container text-on-tertiary-container'
                        : 'bg-surface-container-high text-on-surface hover:bg-primary-container hover:text-on-primary-container'
                    }`}
                  >
                    {isNew && <Sparkles className="h-3 w-3" />}
                    <span>{char.name}</span>
                  </Link>
                </motion.div>
              );
            })}

            <Link
              to={`/books/${encodedBook}/characters${asOfQuery}`}
              className="ml-auto text-xs font-bold text-primary hover:underline"
            >
              {t.reader.openCodex} ({codex.characters.length}) →
            </Link>
          </div>
        </div>
      )}

      {/* Main Reading Sheet */}
      <AnimatePresence mode="wait">
        <motion.article
          key={activeChapter?.id ?? 'empty'}
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -8 }}
          transition={{ y: M3_SPRINGS.spatial.default, opacity: M3_SPRINGS.effects.default }}
          className="rounded-[32px] border border-outline-variant/40 bg-surface-container-lowest px-5 py-6 sm:px-12 sm:py-11"
        >
          {activeChapter && (
            <header className="mx-auto mb-7 max-w-[68ch] space-y-1.5 border-b border-outline-variant/40 pb-5">
              <div className="font-mono text-xs font-bold uppercase tracking-wider text-primary">
                {t.bookmark.volumeFull} {activeChapter.volume_num} · {t.bookmark.chapterFull}{' '}
                {formatChapterNumber(activeChapter.chapter_num)}
              </div>
              {cleanTitle && (
                <h1 className="text-2xl font-extrabold tracking-tight text-on-surface sm:text-3xl">
                  {cleanTitle}
                </h1>
              )}
            </header>
          )}

          {loading ? (
            <div className="mx-auto max-w-[68ch] space-y-4">
              {[1, 2, 3, 4, 5, 6].map((n) => (
                <div
                  key={n}
                  className="h-4 animate-pulse rounded-full bg-surface-container-high"
                  style={{ width: `${n % 2 === 0 ? 92 : 100}%` }}
                />
              ))}
            </div>
          ) : error ? (
            <div className="mx-auto max-w-[68ch] space-y-1.5 rounded-2xl bg-error-container p-5 text-on-error-container">
              <div className="flex items-center gap-2 text-sm font-bold">
                <AlertCircle className="h-4 w-4" />
                <span>{t.reader.chapterTextMissing}</span>
              </div>
              <p className="text-xs opacity-90">{t.reader.chapterTextHint}</p>
            </div>
          ) : (
            <div
              className="mx-auto max-w-[68ch] space-y-4 leading-[1.82] text-on-surface"
              style={{ fontSize: `${fontSize}px` }}
            >
              {blocks.map((block, idx) => {
                if (block.type === 'sceneBreak') {
                  return (
                    <div
                      key={idx}
                      aria-hidden="true"
                      className="flex items-center justify-center gap-3 py-4"
                    >
                      <M3ShapeBadge
                        variant="clover4"
                        interactive={false}
                        className="h-3.5 w-3.5 text-primary/40"
                      />
                      <M3ShapeBadge
                        variant="softBurst"
                        interactive={false}
                        className="h-4 w-4 text-tertiary/55"
                      />
                      <M3ShapeBadge
                        variant="clover4"
                        interactive={false}
                        className="h-3.5 w-3.5 text-primary/40"
                      />
                    </div>
                  );
                }

                if (block.type === 'illustration') {
                  return (
                    <ChapterIllustration
                      key={idx}
                      bookId={bookId}
                      filename={block.filename}
                      alt={block.alt}
                    />
                  );
                }

                if (block.type === 'heading') {
                  return (
                    <h2 key={idx} className="pt-4 text-xl font-extrabold tracking-tight text-on-surface">
                      {block.text}
                    </h2>
                  );
                }

                return <p key={idx}>{block.text}</p>;
              })}
            </div>
          )}

          {/* Bottom Chapter Navigation (nowrap, balanced on 390px) */}
          {!loading && activeChapter && (
            <footer className="mx-auto mt-10 flex max-w-[68ch] items-center justify-between gap-2.5 border-t border-outline-variant/40 pt-6">
              <motion.button
                type="button"
                disabled={!prevChapter}
                whileTap={prevChapter ? { scale: 0.95, borderRadius: '14px' } : undefined}
                transition={M3_SPRINGS.spatial.fast}
                onClick={() => prevChapter && goToChapter(prevChapter.id)}
                className="m3-color-transition inline-flex h-12 cursor-pointer items-center gap-1.5 whitespace-nowrap rounded-full bg-surface-container-high px-4 text-xs font-bold text-on-surface hover:bg-secondary-container hover:text-on-secondary-container disabled:pointer-events-none disabled:opacity-35 sm:px-5 sm:text-sm"
              >
                <ArrowLeft className="h-4 w-4 shrink-0" />
                <span>{t.reader.prevChapterCta}</span>
              </motion.button>

              <motion.button
                type="button"
                disabled={!nextChapter}
                whileTap={nextChapter ? { scale: 0.95, borderRadius: '14px' } : undefined}
                transition={M3_SPRINGS.spatial.fast}
                onClick={() => nextChapter && goToChapter(nextChapter.id)}
                className="m3-color-transition inline-flex h-12 cursor-pointer items-center gap-1.5 whitespace-nowrap rounded-full bg-primary px-5 text-xs font-bold text-on-primary hover:opacity-95 disabled:pointer-events-none disabled:opacity-35 sm:px-6 sm:text-sm"
              >
                <span>{t.reader.nextChapterCta}</span>
                <ArrowRight className="h-4 w-4 shrink-0" />
              </motion.button>
            </footer>
          )}
        </motion.article>
      </AnimatePresence>
    </div>
  );
}
