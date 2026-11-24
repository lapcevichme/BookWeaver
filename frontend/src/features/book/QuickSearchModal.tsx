import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { BookOpen, Search, X } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { M3ShapeBadge, pickShapeForKey, pickToneForKey } from '../../components/M3Shapes';
import { useCodex } from '../../hooks/useCodex';
import { useI18n } from '../../i18n/I18nContext';
import { filterChaptersByQuery, formatChapterNumber } from '../../services/chapters';
import { cleanChapterTitle } from '../../services/formatting';
import { M3_SPRINGS } from '../../theme/m3Tokens';
import type { ChapterInfo } from '../../types/book';

interface QuickSearchModalProps {
  open: boolean;
  bookId: string;
  activeChapterId: string | null;
  chapters: ChapterInfo[];
  onClose: () => void;
}

type SearchEntry =
  | { kind: 'character'; id: string; label: string; sublabel: string; href: string }
  | { kind: 'chapter'; id: string; label: string; sublabel: string; href: string };

export function QuickSearchModal({
  open,
  bookId,
  activeChapterId,
  chapters,
  onClose,
}: QuickSearchModalProps) {
  const { t } = useI18n();
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [selectedIndex, setSelectedIndex] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  const codex = useCodex(open ? bookId : null, open ? activeChapterId : null, null);

  useEffect(() => {
    if (!open) return;
    setQuery('');
    setSelectedIndex(0);
    const timer = window.setTimeout(() => inputRef.current?.focus(), 30);
    return () => window.clearTimeout(timer);
  }, [open]);

  const encodedBook = encodeURIComponent(bookId);
  const asOfQuery = activeChapterId ? `?as_of=${encodeURIComponent(activeChapterId)}` : '';

  const { charEntries, chapterEntries, flatEntries } = useMemo(() => {
    const q = query.trim().toLowerCase();

    const matchedChars = codex.characters
      .filter((c) => {
        if (!q) return true;
        return (
          c.name.toLowerCase().includes(q) ||
          c.aliases.some((a) => a.toLowerCase().includes(q))
        );
      })
      .slice(0, 6)
      .map<SearchEntry>((c) => ({
        kind: 'character',
        id: c.id,
        label: c.name,
        sublabel: c.aliases.slice(0, 3).join(' · '),
        href: `/books/${encodedBook}/characters/${encodeURIComponent(c.id)}${asOfQuery}`,
      }));

    const matchedChapters = (q ? filterChaptersByQuery(chapters, q, 6) : chapters.slice(0, 4)).map<SearchEntry>(
      (ch) => ({
        kind: 'chapter',
        id: ch.id,
        label: `${t.bookmark.volumeShort} ${ch.volume_num} · ${t.bookmark.chapterShort} ${formatChapterNumber(ch.chapter_num)}`,
        sublabel: cleanChapterTitle(ch.title),
        href: `/books/${encodedBook}/read/${encodeURIComponent(ch.id)}`,
      }),
    );

    return {
      charEntries: matchedChars,
      chapterEntries: matchedChapters,
      flatEntries: [...matchedChars, ...matchedChapters],
    };
  }, [query, codex.characters, chapters, encodedBook, asOfQuery, t.bookmark]);

  useEffect(() => {
    setSelectedIndex(0);
  }, [query]);

  const openEntry = (entry: SearchEntry) => {
    navigate(entry.href);
    onClose();
  };

  return (
    <AnimatePresence>
      {open && (
        <div className="fixed inset-0 z-50 flex items-start justify-center px-3 pt-16 sm:pt-24">
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={M3_SPRINGS.effects.fast}
            onClick={onClose}
            className="fixed inset-0 bg-black/45 backdrop-blur-[2px]"
          />

          <motion.div
            role="dialog"
            aria-modal="true"
            aria-label={t.quickSearch.triggerLabel}
            initial={{ opacity: 0, y: -12, scale: 0.97 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -8, scale: 0.97 }}
            transition={{
              y: M3_SPRINGS.spatial.default,
              scale: M3_SPRINGS.spatial.default,
              opacity: M3_SPRINGS.effects.default,
            }}
            className="relative z-10 w-full max-w-lg overflow-hidden rounded-[28px] border border-outline-variant/70 bg-surface-container-high shadow-2xl"
          >
            {/* Search Input */}
            <div className="flex items-center gap-2.5 border-b border-outline-variant/50 px-4 py-3">
              <Search className="h-5 w-5 shrink-0 text-primary" />
              <input
                ref={inputRef}
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Escape') {
                    e.preventDefault();
                    onClose();
                  } else if (e.key === 'ArrowDown') {
                    e.preventDefault();
                    setSelectedIndex((i) =>
                      flatEntries.length > 0 ? (i + 1) % flatEntries.length : 0,
                    );
                  } else if (e.key === 'ArrowUp') {
                    e.preventDefault();
                    setSelectedIndex((i) =>
                      flatEntries.length > 0
                        ? (i - 1 + flatEntries.length) % flatEntries.length
                        : 0,
                    );
                  } else if (e.key === 'Enter' && flatEntries[selectedIndex]) {
                    e.preventDefault();
                    openEntry(flatEntries[selectedIndex]);
                  }
                }}
                placeholder={t.quickSearch.placeholder}
                className="flex-1 bg-transparent text-sm font-medium text-on-surface placeholder:text-on-surface-variant/80 focus:outline-none"
              />
              {query ? (
                <button
                  type="button"
                  onClick={() => setQuery('')}
                  className="flex h-7 w-7 cursor-pointer items-center justify-center rounded-full text-on-surface-variant hover:bg-surface-container-highest"
                >
                  <X className="h-4 w-4" />
                </button>
              ) : (
                <button
                  type="button"
                  onClick={onClose}
                  className="rounded-md bg-surface-container-highest px-1.5 py-0.5 font-mono text-[10px] font-bold text-on-surface-variant"
                >
                  ESC
                </button>
              )}
            </div>

            {/* Results */}
            <div className="max-h-[60vh] space-y-3 overflow-y-auto p-2.5">
              {flatEntries.length === 0 ? (
                <p className="py-8 text-center text-sm text-on-surface-variant">
                  {t.quickSearch.empty}
                </p>
              ) : (
                <>
                  {charEntries.length > 0 && (
                    <div>
                      <div className="px-2.5 py-1 text-[11px] font-bold uppercase tracking-wider text-on-surface-variant">
                        {t.quickSearch.charactersGroup}
                      </div>
                      <ul className="mt-0.5 space-y-0.5">
                        {charEntries.map((entry, idx) => {
                          const selected = selectedIndex === idx;
                          return (
                            <li key={entry.id}>
                              <button
                                type="button"
                                onMouseEnter={() => setSelectedIndex(idx)}
                                onClick={() => openEntry(entry)}
                                className={`m3-color-transition flex w-full cursor-pointer items-center gap-3 rounded-2xl px-3 py-2 text-left ${
                                  selected
                                    ? 'bg-primary-container text-on-primary-container'
                                    : 'text-on-surface hover:bg-surface-container-highest'
                                }`}
                              >
                                <M3ShapeBadge
                                  variant={pickShapeForKey(entry.id)}
                                  interactive={false}
                                  className={`h-8 w-8 shrink-0 ${pickToneForKey(entry.id)}`}
                                  contentClassName="text-xs font-extrabold"
                                >
                                  {entry.label.charAt(0).toUpperCase()}
                                </M3ShapeBadge>
                                <div className="min-w-0 flex-1">
                                  <div className="truncate text-sm font-bold">{entry.label}</div>
                                  {entry.sublabel && (
                                    <div className="truncate text-xs opacity-75">
                                      {entry.sublabel}
                                    </div>
                                  )}
                                </div>
                              </button>
                            </li>
                          );
                        })}
                      </ul>
                    </div>
                  )}

                  {chapterEntries.length > 0 && (
                    <div>
                      <div className="px-2.5 py-1 text-[11px] font-bold uppercase tracking-wider text-on-surface-variant">
                        {t.quickSearch.chaptersGroup}
                      </div>
                      <ul className="mt-0.5 space-y-0.5">
                        {chapterEntries.map((entry, idx) => {
                          const flatIdx = charEntries.length + idx;
                          const selected = selectedIndex === flatIdx;
                          return (
                            <li key={entry.id}>
                              <button
                                type="button"
                                onMouseEnter={() => setSelectedIndex(flatIdx)}
                                onClick={() => openEntry(entry)}
                                className={`m3-color-transition flex w-full cursor-pointer items-center gap-3 rounded-2xl px-3 py-2 text-left ${
                                  selected
                                    ? 'bg-primary-container text-on-primary-container'
                                    : 'text-on-surface hover:bg-surface-container-highest'
                                }`}
                              >
                                <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-surface-container-lowest text-primary">
                                  <BookOpen className="h-4 w-4" />
                                </span>
                                <div className="min-w-0 flex-1">
                                  <div className="truncate font-mono text-xs font-bold">
                                    {entry.label}
                                  </div>
                                  {entry.sublabel && (
                                    <div className="truncate text-xs opacity-80">
                                      {entry.sublabel}
                                    </div>
                                  )}
                                </div>
                              </button>
                            </li>
                          );
                        })}
                      </ul>
                    </div>
                  )}
                </>
              )}
            </div>

            <div className="hidden sm:flex items-center justify-between border-t border-outline-variant/40 bg-surface-container px-4 py-2 font-mono text-[11px] text-on-surface-variant">
              <span>{t.quickSearch.hint}</span>
            </div>
          </motion.div>
        </div>
      )}
    </AnimatePresence>
  );
}
