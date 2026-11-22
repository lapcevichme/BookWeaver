import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  AlertCircle,
  ArrowLeft,
  ArrowRight,
  ChevronDown,
  Highlighter,
  Image as ImageIcon,
  Sparkles,
  Users,
} from 'lucide-react';
import { motion } from 'motion/react';
import { ChapterStepper } from '../../components/ChapterStepper';
import { M3ShapeBadge } from '../../components/M3Shapes';
import { useCodex } from '../../hooks/useCodex';
import { useDelayedLoading } from '../../hooks/useDelayedLoading';
import { useI18n } from '../../i18n/I18nContext';
import { fetchChapterText, getBookImageUrl, getCachedChapterText } from '../../services/api';
import { formatChapterNumber } from '../../services/chapters';
import { cleanChapterTitle, parseChapterBlocks } from '../../services/formatting';
import { readChapterScrollRatio, saveChapterScrollRatio } from '../../services/scrollProgress';
import { createHighlightMatcher, highlightParagraph } from '../../services/textHighlighter';
import { M3_SPRINGS } from '../../theme/m3Tokens';
import type { ChapterText } from '../../types/book';
import { useBookContext } from '../book/BookLayout';
import { CharacterQuickSheet } from './CharacterQuickSheet';
import {
  COLUMN_WIDTH_CLASS,
  LINE_HEIGHT_VALUE,
  READER_WRAPPER_WIDTH_CLASS,
  ReaderSettingsPopover,
  readReaderPrefs,
  saveReaderPrefs,
  type ReaderPrefs,
} from './ReaderSettingsPopover';

const HIGHLIGHT_KEY = 'bw_reader_highlight_names';
const LAST_MENTION_KEY = 'bw_reader_last_mention';

function splitTrailingWord(text: string): [string, string] {
  const match = text.match(/^(.*?)(\S+\s*)$/su);
  return match ? [match[1], match[2]] : ['', text];
}

function splitLeadingWord(text: string): [string, string] {
  const match = text.match(/^(\s*\S+)(.*)$/su);
  return match ? [match[1], match[2]] : [text, ''];
}

function saveLastMention(bookId: string, chapterId: string, mentionKey: string): void {
  try {
    sessionStorage.setItem(LAST_MENTION_KEY, JSON.stringify({ bookId, chapterId, mentionKey }));
  } catch {
    // Ignore storage errors
  }
}

function consumeLastMention(bookId: string, chapterId: string): string | null {
  try {
    const raw = sessionStorage.getItem(LAST_MENTION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { bookId?: string; chapterId?: string; mentionKey?: string };
    if (parsed.bookId === bookId && parsed.chapterId === chapterId && parsed.mentionKey) {
      sessionStorage.removeItem(LAST_MENTION_KEY);
      return parsed.mentionKey;
    }
  } catch {
    // Ignore storage errors
  }
  return null;
}

function readHighlightEnabled(): boolean {
  try {
    const raw = localStorage.getItem(HIGHLIGHT_KEY);
    if (raw === 'false') return false;
  } catch {
    // Ignore storage errors
  }
  return true;
}

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

export function ReaderPage() {
  const { t } = useI18n();
  const { bookId, bookDetails, activeChapter, prevChapter, nextChapter, goToChapter } =
    useBookContext();

  const cachedText =
    bookId && activeChapter ? getCachedChapterText(bookId, activeChapter.id) : null;
  const [chapterText, setChapterText] = useState<ChapterText | null>(cachedText);
  const [loading, setLoading] = useState(Boolean(bookId && activeChapter && !cachedText));
  const [error, setError] = useState<string | null>(null);
  const [prefs, setPrefs] = useState<ReaderPrefs>(readReaderPrefs);
  const [highlightEnabled, setHighlightEnabled] = useState<boolean>(readHighlightEnabled);
  const [mobileCastOpen, setMobileCastOpen] = useState(false);
  const [readProgress, setReadProgress] = useState(0);
  const [sheetCharId, setSheetCharId] = useState<string | null>(null);
  const [activeMentionKey, setActiveMentionKey] = useState<string | null>(null);
  const [flashedMentionKey, setFlashedMentionKey] = useState<string | null>(null);

  const restoredForKeyRef = useRef<string | null>(null);
  const flashTimerRef = useRef<number | null>(null);
  const showSkeleton = useDelayedLoading(loading && !chapterText);
  const showUpdatingFade = useDelayedLoading(loading && Boolean(chapterText));

  const codex = useCodex(bookId, activeChapter?.id ?? null, prevChapter?.id ?? null);

  const charactersById = useMemo(
    () => new Map(codex.characters.map((c) => [c.id, c])),
    [codex.characters],
  );
  const sheetCharacter = sheetCharId ? (charactersById.get(sheetCharId) ?? null) : null;

  const highlightMatcher = useMemo(
    () => (highlightEnabled ? createHighlightMatcher(codex.characters) : null),
    [highlightEnabled, codex.characters],
  );

  useEffect(() => {
    saveReaderPrefs(prefs);
  }, [prefs]);

  useEffect(() => {
    try {
      localStorage.setItem(HIGHLIGHT_KEY, String(highlightEnabled));
    } catch {
      // Ignore storage errors
    }
  }, [highlightEnabled]);

  // Keyboard chapter navigation (← / →) when not typing in inputs or inside a dialog
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey || sheetCharId) return;
      const target = e.target as HTMLElement | null;
      if (
        target &&
        (target.tagName === 'INPUT' ||
          target.tagName === 'TEXTAREA' ||
          target.isContentEditable)
      ) {
        return;
      }
      if (document.querySelector('[role="dialog"]')) return;

      if (e.key === 'ArrowLeft' && prevChapter) {
        e.preventDefault();
        goToChapter(prevChapter.id);
      } else if (e.key === 'ArrowRight' && nextChapter) {
        e.preventDefault();
        goToChapter(nextChapter.id);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [prevChapter, nextChapter, goToChapter, sheetCharId]);

  // Fetch chapter text
  useEffect(() => {
    if (!bookId || !activeChapter) {
      setChapterText(null);
      setLoading(false);
      return;
    }

    setMobileCastOpen(false);
    setSheetCharId(null);

    const cached = getCachedChapterText(bookId, activeChapter.id);
    if (cached) {
      setChapterText(cached);
      setLoading(false);
      setError(null);
      return;
    }

    const controller = new AbortController();
    setLoading(true);
    setError(null);

    fetchChapterText(bookId, activeChapter.id, controller.signal)
      .then((data) => {
        setChapterText(data);
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return;
        setError(err instanceof Error ? err.message : 'Unknown error');
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });

    return () => controller.abort();
  }, [bookId, activeChapter]);

  const triggerMentionFlash = (mentionKey: string) => {
    if (flashTimerRef.current !== null) window.clearTimeout(flashTimerRef.current);
    setFlashedMentionKey(mentionKey);
    flashTimerRef.current = window.setTimeout(() => {
      setFlashedMentionKey(null);
      flashTimerRef.current = null;
    }, 1500);
  };

  useEffect(() => {
    return () => {
      if (flashTimerRef.current !== null) window.clearTimeout(flashTimerRef.current);
    };
  }, []);

  // Restore saved scroll ratio (and flash last clicked mention when returning from Codex)
  useEffect(() => {
    if (!bookId || !activeChapter || !chapterText || chapterText.chapter_id !== activeChapter.id) {
      return;
    }
    const key = `${bookId}::${activeChapter.id}`;
    if (restoredForKeyRef.current === key) return;
    restoredForKeyRef.current = key;

    const savedRatio = readChapterScrollRatio(bookId, activeChapter.id);
    const pendingMention = consumeLastMention(bookId, activeChapter.id);

    window.requestAnimationFrame(() => {
      const scrollable = document.documentElement.scrollHeight - window.innerHeight;
      if (savedRatio > 0.01 && scrollable > 0) {
        window.scrollTo({ top: Math.round(savedRatio * scrollable), behavior: 'instant' });
        setReadProgress(savedRatio);
      } else {
        window.scrollTo({ top: 0, behavior: 'instant' });
        setReadProgress(0);
      }
      if (pendingMention) {
        triggerMentionFlash(pendingMention);
      }
    });
  }, [bookId, activeChapter, chapterText]);

  // Track and persist scroll progress inside the current chapter
  useEffect(() => {
    if (!bookId || !activeChapter) return;
    let saveTimer: number | null = null;

    const handleScroll = () => {
      const scrollable = document.documentElement.scrollHeight - window.innerHeight;
      const ratio = scrollable > 0 ? Math.max(0, Math.min(1, window.scrollY / scrollable)) : 0;
      setReadProgress(ratio);

      if (saveTimer !== null) window.clearTimeout(saveTimer);
      saveTimer = window.setTimeout(() => {
        saveChapterScrollRatio(bookId, activeChapter.id, ratio);
      }, 200);
    };

    window.addEventListener('scroll', handleScroll, { passive: true });
    return () => {
      window.removeEventListener('scroll', handleScroll);
      if (saveTimer !== null) window.clearTimeout(saveTimer);
    };
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
    <div
      className={`mx-auto ${READER_WRAPPER_WIDTH_CLASS[prefs.columnWidth]} space-y-4 transition-[max-width] duration-300 sm:space-y-5`}
    >
      {/* Slim fixed reading progress bar along the very top edge */}
      <div
        aria-hidden="true"
        className="pointer-events-none fixed inset-x-0 top-0 z-50 h-[3px] bg-transparent"
      >
        <div
          className="h-full bg-primary transition-[width] duration-150 ease-out"
          style={{ width: `${Math.round(readProgress * 100)}%` }}
        />
      </div>

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

        <div className="flex shrink-0 items-center gap-1.5">
          {/* Toggle character name highlighting in prose */}
          <motion.button
            type="button"
            whileTap={{ scale: 0.92, borderRadius: '12px' }}
            transition={M3_SPRINGS.spatial.fast}
            onClick={() => setHighlightEnabled((v) => !v)}
            aria-pressed={highlightEnabled}
            aria-label={t.reader.highlightNames}
            title={t.reader.highlightNames}
            className={`m3-color-transition inline-flex h-11 cursor-pointer items-center gap-1.5 rounded-full px-3 text-xs font-bold sm:px-3.5 ${
              highlightEnabled
                ? 'bg-primary-container text-on-primary-container'
                : 'bg-surface-container-high text-on-surface-variant hover:bg-surface-container-highest'
            }`}
          >
            <Highlighter className="h-4 w-4" />
            <span className="hidden md:inline">{t.reader.highlightNames}</span>
          </motion.button>

          {/* Reader typography & layout settings popover (Aa) */}
          <ReaderSettingsPopover prefs={prefs} onChange={setPrefs} />
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
                <motion.button
                  key={char.id}
                  type="button"
                  whileHover={{ scale: 1.04 }}
                  whileTap={{ scale: 0.95 }}
                  onClick={() => setSheetCharId(char.id)}
                  className={`m3-color-transition inline-flex h-8 cursor-pointer items-center gap-1.5 rounded-full px-3 text-xs font-bold ${
                    isNew
                      ? 'bg-tertiary-container text-on-tertiary-container'
                      : 'bg-surface-container-high text-on-surface hover:bg-primary-container hover:text-on-primary-container'
                  }`}
                >
                  {isNew && <Sparkles className="h-3 w-3" />}
                  <span>{char.name}</span>
                </motion.button>
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
      <article
        className={`rounded-[32px] border border-outline-variant/40 bg-surface-container-lowest px-5 py-6 transition-opacity duration-150 sm:px-12 sm:py-11 ${
          showUpdatingFade ? 'opacity-65' : 'opacity-100'
        }`}
      >
        {activeChapter && (
          <header
            className={`mx-auto mb-7 ${COLUMN_WIDTH_CLASS[prefs.columnWidth]} space-y-1.5 border-b border-outline-variant/40 pb-5`}
          >
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

        {showSkeleton ? (
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
        ) : chapterText ? (
          <motion.div
            key={chapterText.chapter_id}
            initial={{ opacity: 0, y: 14 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{
              y: M3_SPRINGS.spatial.fast,
              opacity: M3_SPRINGS.effects.default,
            }}
            className={`mx-auto ${COLUMN_WIDTH_CLASS[prefs.columnWidth]} space-y-4 text-on-surface`}
            style={{
              fontSize: `${prefs.fontSize}px`,
              lineHeight: LINE_HEIGHT_VALUE[prefs.lineHeight],
              fontFamily:
                prefs.fontFamily === 'serif'
                  ? 'Georgia, "Times New Roman", serif'
                  : 'var(--font-sans)',
            }}
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

              if (!highlightMatcher) {
                return <p key={idx}>{block.text}</p>;
              }

              const segments = highlightParagraph(block.text, highlightMatcher);
              const flashedSegIdx =
                flashedMentionKey && flashedMentionKey.startsWith(`${idx}:`)
                  ? Number(flashedMentionKey.split(':')[1])
                  : -1;

              return (
                <p key={idx}>
                  {segments.map((seg, sIdx) => {
                    const mentionKey = `${idx}:${sIdx}`;
                    const isFlashed = flashedSegIdx === sIdx;

                    if (seg.characterId) {
                      const openChar = () => {
                        setActiveMentionKey(mentionKey);
                        if (activeChapter) {
                          saveLastMention(bookId, activeChapter.id, mentionKey);
                        }
                        setSheetCharId(seg.characterId!);
                      };
                      return (
                        <span
                          key={sIdx}
                          role="button"
                          tabIndex={0}
                          onClick={openChar}
                          onKeyDown={(e) => {
                            if (e.key === 'Enter' || e.key === ' ') {
                              e.preventDefault();
                              openChar();
                            }
                          }}
                          className={`inline cursor-pointer border-b border-dashed transition-colors duration-300 ${
                            isFlashed
                              ? 'border-transparent bg-tertiary-container/80 text-on-tertiary-container'
                              : 'border-primary/60 bg-transparent text-inherit hover:bg-primary-container/60 hover:text-on-primary-container'
                          }`}
                        >
                          {seg.text}
                        </span>
                      );
                    }

                    const prevIsChar = Boolean(segments[sIdx - 1]?.characterId);
                    const nextIsChar = Boolean(segments[sIdx + 1]?.characterId);
                    const flashLeading = prevIsChar && flashedSegIdx === sIdx - 1;
                    const flashTrailing = nextIsChar && flashedSegIdx === sIdx + 1;

                    // Keep adjacent word spans mounted so fade-in and fade-out stay 100% synced with the name
                    if (prevIsChar || nextIsChar) {
                      let leadWord = '';
                      let middle = seg.text;
                      let trailWord = '';

                      if (prevIsChar) {
                        const [w, rest] = splitLeadingWord(middle);
                        leadWord = w;
                        middle = rest;
                      }
                      if (nextIsChar) {
                        const [rest, w] = splitTrailingWord(middle);
                        middle = rest;
                        trailWord = w;
                      }

                      return (
                        <span key={sIdx}>
                          {leadWord && (
                            <span
                              className={`rounded-r-sm transition-colors duration-300 ${
                                flashLeading
                                  ? 'bg-tertiary-container/80 text-on-tertiary-container'
                                  : 'bg-transparent text-inherit'
                              }`}
                            >
                              {leadWord}
                            </span>
                          )}
                          {middle}
                          {trailWord && (
                            <span
                              className={`rounded-l-sm transition-colors duration-300 ${
                                flashTrailing
                                  ? 'bg-tertiary-container/80 text-on-tertiary-container'
                                  : 'bg-transparent text-inherit'
                              }`}
                            >
                              {trailWord}
                            </span>
                          )}
                        </span>
                      );
                    }

                    return <span key={sIdx}>{seg.text}</span>;
                  })}
                </p>
              );
            })}
          </motion.div>
        ) : (
          <div className="min-h-[45vh]" />
        )}

        {/* Bottom Chapter Navigation */}
        {chapterText && activeChapter && (
          <footer
            className={`mx-auto mt-10 flex ${COLUMN_WIDTH_CLASS[prefs.columnWidth]} items-center justify-between gap-2.5 border-t border-outline-variant/40 pt-6`}
          >
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
      </article>

      {/* Quick Character Sheet (opens right over the prose without losing scroll position) */}
      <CharacterQuickSheet
        bookId={bookId}
        character={sheetCharacter}
        activeChapterId={activeChapter?.id ?? null}
        chapters={bookDetails.chapters}
        onClose={() => {
          setSheetCharId(null);
          if (activeMentionKey) {
            if (activeChapter) consumeLastMention(bookId, activeChapter.id);
            triggerMentionFlash(activeMentionKey);
            setActiveMentionKey(null);
          }
        }}
        onSelectRelated={(otherId) => setSheetCharId(otherId)}
      />
    </div>
  );
}
