import { useEffect, useMemo } from 'react';
import { Link } from 'react-router-dom';
import { ArrowUpRight, Eye, Sparkles, Tag, X } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { M3ShapeBadge, pickShapeForKey, pickToneForKey } from '../../components/M3Shapes';
import { useI18n } from '../../i18n/I18nContext';
import { formatChapterRef } from '../../services/codex';
import { M3_SPRINGS } from '../../theme/m3Tokens';
import type { ChapterInfo } from '../../types/book';
import type { CharacterAsOf } from '../../types/knowledge';

interface CharacterQuickSheetProps {
  bookId: string;
  character: CharacterAsOf | null;
  activeChapterId: string | null;
  chapters: ChapterInfo[];
  onClose: () => void;
  onSelectRelated: (charId: string) => void;
}

export function CharacterQuickSheet({
  bookId,
  character,
  activeChapterId,
  chapters,
  onClose,
  onSelectRelated,
}: CharacterQuickSheetProps) {
  const { t } = useI18n();

  useEffect(() => {
    if (!character) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [character, onClose]);

  const chaptersById = useMemo(
    () => new Map(chapters.map((c) => [c.id, c])),
    [chapters],
  );
  const chapterOrder = useMemo(
    () => new Map(chapters.map((c, idx) => [c.id, idx])),
    [chapters],
  );

  const fmtChapter = (id: string | null) =>
    formatChapterRef(id, chaptersById, t.bookmark.volumeShort, t.bookmark.chapterShort);

  const latestFacts = useMemo(() => {
    if (!character) return [];
    return [...character.facts]
      .filter((f) => !f.until)
      .sort((a, b) => {
        const idxA = a.chapter ? (chapterOrder.get(a.chapter) ?? -1) : -1;
        const idxB = b.chapter ? (chapterOrder.get(b.chapter) ?? -1) : -1;
        return idxB - idxA;
      })
      .slice(0, 4);
  }, [character, chapterOrder]);

  const encodedBook = encodeURIComponent(bookId);
  const asOfQuery = activeChapterId ? `?as_of=${encodeURIComponent(activeChapterId)}` : '';

  return (
    <AnimatePresence>
      {character && (
        <div className="fixed inset-0 z-50 flex items-end justify-end sm:items-stretch">
          {/* Scrim */}
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={M3_SPRINGS.effects.fast}
            onClick={onClose}
            className="fixed inset-0 bg-black/45 backdrop-blur-[2px]"
          />

          {/* Adaptive M3 Sheet: Bottom Sheet on mobile, Side Sheet on desktop */}
          <motion.aside
            role="dialog"
            aria-modal="true"
            aria-label={character.name}
            initial={{ y: 40, opacity: 0 }}
            animate={{ y: 0, opacity: 1 }}
            exit={{ y: 32, opacity: 0 }}
            transition={{
              y: M3_SPRINGS.spatial.fast,
              opacity: M3_SPRINGS.effects.fast,
            }}
            className="relative z-10 flex max-h-[84vh] w-full flex-col overflow-hidden rounded-t-[32px] border-t border-outline-variant/60 bg-surface-container-low shadow-2xl sm:h-full sm:max-h-none sm:w-[440px] sm:rounded-t-none sm:rounded-l-[32px] sm:border-t-0 sm:border-l"
          >
            {/* Mobile drag handle */}
            <div className="flex justify-center pt-3 pb-1 sm:hidden">
              <span className="h-1 w-10 rounded-full bg-outline-variant" />
            </div>

            {/* Sheet Header */}
            <div className="flex items-start justify-between gap-3 border-b border-outline-variant/40 px-5 py-4 sm:px-6 sm:py-5">
              <div className="flex min-w-0 items-center gap-3.5">
                <M3ShapeBadge
                  variant={pickShapeForKey(character.id)}
                  className={`h-14 w-14 shrink-0 ${pickToneForKey(character.id)}`}
                  contentClassName="text-xl font-extrabold"
                >
                  {character.name.trim().charAt(0).toUpperCase() || '?'}
                </M3ShapeBadge>

                <div className="min-w-0 space-y-1">
                  <h2 className="truncate text-xl font-extrabold tracking-tight text-on-surface">
                    {character.name}
                  </h2>
                  <div className="flex flex-wrap items-center gap-1.5">
                    {character.role_tier && (
                      <span className="rounded-full bg-primary px-2.5 py-0.5 text-[11px] font-bold text-on-primary">
                        {t.codex.roles[character.role_tier as keyof typeof t.codex.roles] ??
                          character.role_tier}
                      </span>
                    )}
                    {character.first_chapter && (
                      <span className="font-mono text-[11px] text-on-surface-variant">
                        {t.codex.firstSeen}: {fmtChapter(character.first_chapter)}
                      </span>
                    )}
                  </div>
                </div>
              </div>

              <motion.button
                type="button"
                whileTap={{ scale: 0.9 }}
                transition={M3_SPRINGS.spatial.fast}
                onClick={onClose}
                aria-label={t.reader.closeSheet}
                className="flex h-9 w-9 shrink-0 cursor-pointer items-center justify-center rounded-full bg-surface-container-high text-on-surface hover:bg-surface-container-highest"
              >
                <X className="h-4 w-4" />
              </motion.button>
            </div>

            {/* Scrollable Body */}
            <div className="flex-1 space-y-5 overflow-y-auto px-5 py-4 sm:px-6 sm:py-5">
              {character.aliases.length > 0 && (
                <div className="flex flex-wrap items-center gap-1.5">
                  <Tag className="mr-0.5 h-3.5 w-3.5 text-on-surface-variant" />
                  {character.aliases.map((alias) => (
                    <span
                      key={alias}
                      className="rounded-lg bg-surface-container-high px-2.5 py-0.5 text-xs font-semibold text-on-surface-variant"
                    >
                      {alias}
                    </span>
                  ))}
                </div>
              )}

              {character.reveals.length > 0 && (
                <div className="space-y-2 rounded-2xl bg-tertiary-container p-3.5 text-on-tertiary-container">
                  <div className="flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-wider">
                    <Eye className="h-3.5 w-3.5" />
                    <span>{t.codex.revealsTitle}</span>
                  </div>
                  {character.reveals.map((rev) => (
                    <button
                      key={rev.link_id}
                      type="button"
                      onClick={() => onSelectRelated(rev.other_character_id)}
                      className="flex w-full cursor-pointer items-center justify-between gap-2 rounded-xl bg-surface-container-lowest/90 px-3 py-2 text-left text-xs font-bold text-on-surface hover:bg-surface-container-lowest"
                    >
                      <span>{rev.other_character_name}</span>
                      <ArrowUpRight className="h-3.5 w-3.5 text-tertiary" />
                    </button>
                  ))}
                </div>
              )}

              {character.description && (
                <p className="text-sm leading-relaxed text-on-surface whitespace-pre-line">
                  {character.description}
                </p>
              )}

              {latestFacts.length > 0 && (
                <div className="space-y-2">
                  <div className="text-xs font-bold uppercase tracking-wider text-on-surface-variant">
                    {t.reader.latestFacts}
                  </div>
                  <ul className="space-y-2">
                    {latestFacts.map((fact, idx) => {
                      const isNew = Boolean(activeChapterId && fact.chapter === activeChapterId);
                      return (
                        <li
                          key={fact.id ?? idx}
                          className={`rounded-2xl border p-3 text-xs leading-relaxed ${
                            isNew
                              ? 'border-tertiary/40 bg-tertiary-container/40 text-on-surface'
                              : 'border-outline-variant/40 bg-surface-container-lowest text-on-surface'
                          }`}
                        >
                          <div className="mb-1 flex items-center gap-1.5">
                            {fact.kind && (
                              <span className="rounded bg-surface-container-high px-1.5 py-0.5 text-[10px] font-bold text-on-surface-variant">
                                {t.codex.factKinds[fact.kind] ?? fact.kind}
                              </span>
                            )}
                            {fact.chapter && (
                              <span className="font-mono text-[10px] text-on-surface-variant">
                                {fmtChapter(fact.chapter)}
                              </span>
                            )}
                            {isNew && (
                              <span className="inline-flex items-center gap-0.5 rounded-full bg-tertiary px-1.5 py-0.5 text-[10px] font-bold text-on-tertiary">
                                <Sparkles className="h-2.5 w-2.5" />
                                {t.codex.newBadgeShort}
                              </span>
                            )}
                          </div>
                          <p>{fact.fact}</p>
                        </li>
                      );
                    })}
                  </ul>
                </div>
              )}
            </div>

            {/* Footer CTA */}
            <div className="border-t border-outline-variant/40 bg-surface-container p-4 sm:px-6">
              <Link
                to={`/books/${encodedBook}/characters/${encodeURIComponent(character.id)}${asOfQuery}`}
                className="flex h-11 w-full items-center justify-center gap-2 rounded-full bg-primary text-sm font-bold text-on-primary transition-opacity hover:opacity-95"
              >
                <span>{t.reader.openFullDossier}</span>
                <ArrowUpRight className="h-4 w-4" />
              </Link>
            </div>
          </motion.aside>
        </div>
      )}
    </AnimatePresence>
  );
}
