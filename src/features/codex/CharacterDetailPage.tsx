import { useEffect, useMemo, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import {
  ArrowLeft,
  ArrowUpRight,
  BookOpenText,
  Eye,
  History,
  ListChecks,
  Sparkles,
  Tag,
} from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { ChapterStepper } from '../../components/ChapterStepper';
import { M3ShapeBadge, pickShapeForKey, pickToneForKey } from '../../components/M3Shapes';
import { useCodex } from '../../hooks/useCodex';
import { useDelayedLoading } from '../../hooks/useDelayedLoading';
import { useI18n } from '../../i18n/I18nContext';
import { formatChapterRef } from '../../services/codex';
import { M3_SPRINGS } from '../../theme/m3Tokens';
import type { CharacterDetailTab } from '../../types/book';
import { useBookContext } from '../book/BookLayout';

export function CharacterDetailPage() {
  const { t } = useI18n();
  const { charId = '' } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const { bookId, bookDetails, activeChapter, prevChapter, nextChapter, goToChapter } =
    useBookContext();

  const codex = useCodex(bookId, activeChapter?.id ?? null, prevChapter?.id ?? null);
  const [factKind, setFactKind] = useState<string>('all');
  const showSkeleton = useDelayedLoading(codex.loading && codex.characters.length === 0);

  const rawTab = searchParams.get('tab');
  const activeTab: CharacterDetailTab =
    rawTab === 'chronicle' || rawTab === 'facts' ? rawTab : 'overview';

  const setTab = (nextTab: CharacterDetailTab) => {
    const next = new URLSearchParams(searchParams);
    next.set('tab', nextTab);
    setSearchParams(next, { replace: true });
  };

  useEffect(() => {
    setFactKind('all');
  }, [charId]);

  const character = useMemo(
    () => codex.characters.find((c) => c.id === charId) ?? null,
    [codex.characters, charId],
  );

  const chaptersById = useMemo(
    () => new Map(bookDetails.chapters.map((c) => [c.id, c])),
    [bookDetails.chapters],
  );
  const chapterOrder = useMemo(
    () => new Map(bookDetails.chapters.map((c, idx) => [c.id, idx])),
    [bookDetails.chapters],
  );

  const fmtChapter = (id: string | null) =>
    formatChapterRef(id, chaptersById, t.bookmark.volumeShort, t.bookmark.chapterShort);

  const encodedBook = encodeURIComponent(bookId);
  const asOfQuery = activeChapter ? `?as_of=${encodeURIComponent(activeChapter.id)}` : '';

  if (showSkeleton) {
    return (
      <div className="mx-auto max-w-4xl space-y-4">
        <div className="h-32 animate-pulse rounded-[28px] bg-surface-container" />
        <div className="h-64 animate-pulse rounded-[28px] bg-surface-container" />
      </div>
    );
  }

  if (codex.loading && !character) {
    return <div className="mx-auto max-w-4xl min-h-[50vh]" />;
  }

  if (!character) {
    return (
      <div className="mx-auto max-w-4xl space-y-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <Link
            to={`/books/${encodedBook}/characters${asOfQuery}`}
            className="inline-flex h-10 w-fit items-center gap-2 rounded-full bg-surface-container-high px-4 text-sm font-bold text-on-surface"
          >
            <ArrowLeft className="h-4 w-4" />
            <span>{t.codex.backToList}</span>
          </Link>
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
        <div className="rounded-[28px] bg-surface-container-low p-10 text-center text-sm text-on-surface-variant">
          {t.codex.notFound}
        </div>
      </div>
    );
  }

  const availableFactKinds = Array.from(
    new Set(character.facts.map((f) => f.kind).filter(Boolean)),
  );
  const filteredFacts =
    factKind === 'all' ? character.facts : character.facts.filter((f) => f.kind === factKind);

  // Sort facts newest-first so newly unlocked facts appear at the very top
  const sortNewestFirst = (list: typeof filteredFacts) =>
    [...list].sort((a, b) => {
      const idxA = a.chapter ? (chapterOrder.get(a.chapter) ?? -1) : -1;
      const idxB = b.chapter ? (chapterOrder.get(b.chapter) ?? -1) : -1;
      return idxB - idxA;
    });

  const activeFacts = sortNewestFirst(filteredFacts.filter((f) => !f.until));
  const retiredFacts = sortNewestFirst(filteredFacts.filter((f) => Boolean(f.until)));

  const sortedMentions = Object.entries(character.chapter_mentions).sort(([a], [b]) => {
    const idxA = chapterOrder.get(a) ?? -1;
    const idxB = chapterOrder.get(b) ?? -1;
    return idxB - idxA;
  });

  const roleKey = (character.role_tier ?? 'background') as keyof typeof t.codex.roles;
  const roleLabel = t.codex.roles[roleKey] ?? character.role_tier ?? '';
  const initial = character.name.trim().charAt(0).toUpperCase() || '?';
  const shape = pickShapeForKey(character.id);
  const tone = pickToneForKey(character.id);

  const tabs: { id: CharacterDetailTab; label: string; count?: number; icon: typeof BookOpenText }[] = [
    { id: 'overview', label: t.codex.tabOverview, icon: BookOpenText },
    {
      id: 'chronicle',
      label: t.codex.tabChronicle,
      count: sortedMentions.length,
      icon: History,
    },
    {
      id: 'facts',
      label: t.codex.tabFacts,
      count: character.facts.length,
      icon: ListChecks,
    },
  ];

  return (
    <div className="mx-auto max-w-4xl space-y-5">
      {/* Top Bar: Back + Live ChapterStepper to inspect character evolution across chapters */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <motion.div whileTap={{ scale: 0.96 }} className="inline-block w-fit">
          <Link
            to={`/books/${encodedBook}/characters${asOfQuery}`}
            className="m3-color-transition inline-flex h-11 items-center gap-2 rounded-full bg-surface-container-high px-4 text-sm font-bold text-on-surface hover:bg-primary-container hover:text-on-primary-container"
          >
            <ArrowLeft className="h-4 w-4" />
            <span>{t.codex.backToList}</span>
          </Link>
        </motion.div>

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

      {/* Expressive Character Hero Header */}
      <motion.header
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ y: M3_SPRINGS.spatial.default, opacity: M3_SPRINGS.effects.default }}
        className="relative overflow-hidden flex flex-col gap-5 rounded-[32px] border border-outline-variant/50 bg-surface-container p-5 sm:flex-row sm:items-center sm:p-7"
      >
        <M3ShapeBadge
          variant={shape}
          interactive={false}
          className="pointer-events-none absolute -right-10 -top-10 h-44 w-44 text-primary/10"
        />

        <M3ShapeBadge
          variant={shape}
          className={`relative z-10 h-20 w-20 shrink-0 sm:h-24 sm:w-24 ${tone}`}
          contentClassName="text-3xl font-extrabold"
        >
          {initial}
        </M3ShapeBadge>

        <div className="relative z-10 min-w-0 flex-1 space-y-2">
          <div className="flex flex-wrap items-center gap-2">
            {roleLabel && (
              <span className="rounded-full bg-primary px-3 py-1 text-xs font-bold text-on-primary">
                {roleLabel}
              </span>
            )}
            {character.first_chapter && (
              <span className="rounded-full bg-surface-container-highest px-3 py-1 font-mono text-xs font-semibold text-on-surface-variant">
                {t.codex.firstSeen}: {fmtChapter(character.first_chapter)}
              </span>
            )}
          </div>

          <h1 className="text-2xl font-extrabold tracking-tight sm:text-3xl">{character.name}</h1>

          {character.aliases.length > 0 && (
            <div className="flex flex-wrap items-center gap-1.5">
              <Tag className="mr-0.5 h-3.5 w-3.5 text-on-surface-variant" />
              {character.aliases.map((alias) => (
                <span
                  key={alias}
                  className="rounded-lg bg-surface-container-lowest px-2.5 py-0.5 text-xs font-semibold text-on-surface-variant"
                >
                  {alias}
                </span>
              ))}
            </div>
          )}
        </div>
      </motion.header>

      {/* 3 URL-synced Tabs (grid-cols-3 on mobile so pills never overflow at 390px) */}
      <div
        role="tablist"
        className="grid w-full grid-cols-3 gap-1 rounded-full bg-surface-container-high p-1.5 sm:inline-flex sm:w-auto"
      >
        {tabs.map((tab) => {
          const active = activeTab === tab.id;
          const Icon = tab.icon;
          return (
            <button
              key={tab.id}
              type="button"
              role="tab"
              aria-selected={active}
              onClick={() => setTab(tab.id)}
              className={`relative flex h-10 min-w-0 cursor-pointer items-center justify-center gap-1 rounded-full px-2 text-xs font-bold transition-colors sm:px-4 ${
                active ? 'text-on-primary' : 'text-on-surface-variant hover:text-on-surface'
              }`}
            >
              {active && (
                <motion.span
                  layoutId="char-detail-tab-pill"
                  transition={M3_SPRINGS.spatial.fast}
                  className="absolute inset-0 rounded-full bg-primary"
                />
              )}
              <Icon className="relative z-10 hidden h-3.5 w-3.5 shrink-0 sm:block" />
              <span className="relative z-10 truncate">{tab.label}</span>
              {tab.count !== undefined && (
                <span
                  className={`relative z-10 shrink-0 font-mono text-[11px] ${
                    active ? 'opacity-90' : 'opacity-70'
                  }`}
                >
                  {tab.count}
                </span>
              )}
            </button>
          );
        })}
      </div>

      {/* Animated Tab Panel */}
      <AnimatePresence mode="wait">
        <motion.section
          key={activeTab}
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -6 }}
          transition={{ y: M3_SPRINGS.spatial.fast, opacity: M3_SPRINGS.effects.fast }}
          className="rounded-[32px] border border-outline-variant/40 bg-surface-container-lowest p-5 sm:p-8"
        >
          {activeTab === 'overview' && (
            <div className="space-y-6">
              {character.reveals.length > 0 && (
                <div className="space-y-3 rounded-[24px] bg-tertiary-container p-4 text-on-tertiary-container sm:p-5">
                  <div className="flex items-center gap-2 text-xs font-bold uppercase tracking-wider">
                    <Eye className="h-4 w-4" />
                    <span>{t.codex.revealsTitle}</span>
                  </div>
                  <div className="space-y-2.5">
                    {character.reveals.map((reveal) => (
                      <div
                        key={reveal.link_id}
                        className="flex flex-col justify-between gap-3 rounded-2xl bg-surface-container-lowest/90 p-3.5 text-on-surface sm:flex-row sm:items-center"
                      >
                        <div className="space-y-1">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="text-sm font-bold">{reveal.other_character_name}</span>
                            <span className="rounded-full bg-tertiary-container px-2.5 py-0.5 text-[11px] font-bold text-on-tertiary-container">
                              {t.codex.relations[reveal.relation] ?? reveal.relation}
                            </span>
                            {reveal.revealed_in && (
                              <span className="font-mono text-xs text-on-surface-variant">
                                {t.codex.revealedIn} {fmtChapter(reveal.revealed_in)}
                              </span>
                            )}
                          </div>
                          {reveal.evidence && (
                            <p className="text-xs leading-relaxed text-on-surface-variant">
                              {reveal.evidence}
                            </p>
                          )}
                        </div>
                        <Link
                          to={`/books/${encodedBook}/characters/${encodeURIComponent(reveal.other_character_id)}${asOfQuery}`}
                          className="inline-flex h-9 shrink-0 items-center gap-1 rounded-full bg-tertiary px-3.5 text-xs font-bold text-on-tertiary"
                        >
                          <span>{reveal.other_character_name}</span>
                          <ArrowUpRight className="h-3.5 w-3.5" />
                        </Link>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              <div className="space-y-2.5">
                <div className="font-mono text-xs font-semibold uppercase tracking-wider text-primary">
                  {t.codex.descriptionAsOf}{' '}
                  {fmtChapter(character.description_from ?? activeChapter?.id ?? null)}
                </div>
                {character.description ? (
                  <p className="max-w-[68ch] text-[15px] leading-[1.8] text-on-surface whitespace-pre-line">
                    {character.description}
                  </p>
                ) : (
                  <p className="text-sm text-on-surface-variant">{t.codex.noDescription}</p>
                )}
              </div>
            </div>
          )}

          {activeTab === 'chronicle' && (
            <div>
              {sortedMentions.length === 0 ? (
                <p className="text-sm text-on-surface-variant">{t.codex.noChronicle}</p>
              ) : (
                <ol className="ml-2 space-y-4 border-l-2 border-outline-variant/60 pl-5">
                  {sortedMentions.map(([chapterId, summary]) => {
                    const isCurrent = chapterId === activeChapter?.id;
                    return (
                      <li key={chapterId} className="relative">
                        <span
                          className={`absolute -left-[25px] top-1.5 h-3 w-3 rounded-full ${
                            isCurrent
                              ? 'bg-tertiary ring-4 ring-tertiary-container'
                              : 'bg-primary'
                          }`}
                        />
                        <Link
                          to={`/books/${encodedBook}/read/${encodeURIComponent(chapterId)}`}
                          className="inline-block font-mono text-xs font-bold text-primary hover:underline"
                        >
                          {fmtChapter(chapterId)}
                        </Link>
                        <p className="mt-1 max-w-[68ch] text-sm leading-relaxed text-on-surface">
                          {summary}
                        </p>
                      </li>
                    );
                  })}
                </ol>
              )}
            </div>
          )}

          {activeTab === 'facts' && (
            <div className="space-y-5">
              {availableFactKinds.length > 1 && (
                <div className="flex flex-wrap gap-1.5">
                  <motion.button
                    type="button"
                    whileTap={{ scale: 0.94 }}
                    onClick={() => setFactKind('all')}
                    className={`h-9 cursor-pointer rounded-full px-3.5 text-xs font-bold ${
                      factKind === 'all'
                        ? 'bg-secondary-container text-on-secondary-container'
                        : 'bg-surface-container text-on-surface-variant hover:bg-surface-container-high'
                    }`}
                  >
                    {t.codex.factKindAll}
                  </motion.button>
                  {availableFactKinds.map((kind) => (
                    <motion.button
                      key={kind}
                      type="button"
                      whileTap={{ scale: 0.94 }}
                      onClick={() => setFactKind(kind)}
                      className={`h-9 cursor-pointer rounded-full px-3.5 text-xs font-bold ${
                        factKind === kind
                          ? 'bg-secondary-container text-on-secondary-container'
                          : 'bg-surface-container text-on-surface-variant hover:bg-surface-container-high'
                      }`}
                    >
                      {t.codex.factKinds[kind] ?? kind}
                    </motion.button>
                  ))}
                </div>
              )}

              {filteredFacts.length === 0 ? (
                <p className="text-sm text-on-surface-variant">{t.codex.noFacts}</p>
              ) : (
                <div className="space-y-5">
                  {activeFacts.length > 0 && (
                    <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
                      {activeFacts.map((fact, idx) => {
                        const isNewNow = Boolean(
                          activeChapter && fact.chapter === activeChapter.id,
                        );
                        return (
                          <div
                            key={fact.id ?? `${fact.fact}-${idx}`}
                            className={`rounded-2xl border p-3.5 text-sm leading-relaxed ${
                              isNewNow
                                ? 'border-tertiary/40 bg-tertiary-container/40 text-on-surface'
                                : 'border-outline-variant/40 bg-surface-container-low text-on-surface'
                            }`}
                          >
                            <div className="mb-1.5 flex flex-wrap items-center gap-2">
                              {fact.kind && (
                                <span className="rounded-md bg-surface-container-high px-2 py-0.5 text-[11px] font-bold text-on-surface-variant">
                                  {t.codex.factKinds[fact.kind] ?? fact.kind}
                                </span>
                              )}
                              {fact.chapter && (
                                <span className="font-mono text-[11px] text-on-surface-variant">
                                  {fmtChapter(fact.chapter)}
                                </span>
                              )}
                              {isNewNow && (
                                <span className="inline-flex items-center gap-1 rounded-full bg-tertiary px-2 py-0.5 text-[10px] font-bold text-on-tertiary">
                                  <Sparkles className="h-2.5 w-2.5" />
                                  {t.codex.newBadgeShort}
                                </span>
                              )}
                            </div>
                            <p>{fact.fact}</p>
                          </div>
                        );
                      })}
                    </div>
                  )}

                  {retiredFacts.length > 0 && (
                    <div className="space-y-2">
                      <div className="text-xs font-bold uppercase tracking-wider text-on-surface-variant">
                        {t.codex.factsRetired}
                      </div>
                      <ul className="space-y-1.5">
                        {retiredFacts.map((fact, idx) => (
                          <li
                            key={fact.id ?? `retired-${idx}`}
                            className="rounded-2xl bg-surface-container/60 p-3 text-sm text-on-surface-variant opacity-75"
                          >
                            <span className="line-through">{fact.fact}</span>
                            {fact.until && (
                              <span className="ml-2 font-mono text-xs">
                                ({t.codex.untilChapter} {fmtChapter(fact.until)})
                              </span>
                            )}
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}
                </div>
              )}
            </div>
          )}
        </motion.section>
      </AnimatePresence>
    </div>
  );
}
