import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Eye, Search, Sparkles, Users, X } from 'lucide-react';
import { motion } from 'motion/react';
import { ChapterStepper } from '../../components/ChapterStepper';
import { M3ShapeBadge, pickShapeForKey, pickToneForKey } from '../../components/M3Shapes';
import { useCodex } from '../../hooks/useCodex';
import { useDelayedLoading } from '../../hooks/useDelayedLoading';
import { useI18n } from '../../i18n/I18nContext';
import { filterAndSortCharacters, type RoleFilter } from '../../services/codex';
import { formatFactCount } from '../../services/formatting';
import { M3_SPRINGS } from '../../theme/m3Tokens';
import type { CharacterAsOf, CharacterDelta } from '../../types/knowledge';
import { useBookContext } from '../book/BookLayout';

const ROLE_FILTERS: RoleFilter[] = ['all', 'protagonist', 'major', 'minor', 'background'];

function CharacterGridCard({
  bookId,
  character,
  delta,
  asOfQuery,
}: {
  bookId: string;
  character: CharacterAsOf;
  delta?: CharacterDelta;
  asOfQuery: string;
}) {
  const { locale, t } = useI18n();

  const roleKey = (character.role_tier ?? 'background') as keyof typeof t.codex.roles;
  const roleLabel = t.codex.roles[roleKey] ?? character.role_tier ?? '';
  const initial = character.name.trim().charAt(0).toUpperCase() || '?';
  const href = `/books/${encodeURIComponent(bookId)}/characters/${encodeURIComponent(character.id)}${asOfQuery}`;
  const shape = pickShapeForKey(character.id);
  const tone = pickToneForKey(character.id);

  return (
    <motion.div
      layout="position"
      initial={{ opacity: 0, scale: 0.96, y: 10 }}
      animate={{ opacity: 1, scale: 1, y: 0 }}
      whileHover={{ y: -3 }}
      whileTap={{ scale: 0.98 }}
      transition={{
        layout: M3_SPRINGS.spatial.fast,
        y: M3_SPRINGS.spatial.fast,
        scale: M3_SPRINGS.spatial.fast,
        opacity: M3_SPRINGS.effects.fast,
      }}
      className="h-full"
    >
      <Link
        to={href}
        className="m3-color-transition group flex h-full flex-col justify-between gap-3 rounded-[28px] border border-outline-variant/50 bg-surface-container-lowest p-4 hover:border-primary/40 hover:bg-surface-container-low"
      >
        <div className="flex items-start gap-3.5">
          <M3ShapeBadge
            variant={shape}
            className={`h-13 w-13 shrink-0 ${tone}`}
            contentClassName="text-lg font-extrabold"
          >
            {initial}
          </M3ShapeBadge>

          <div className="min-w-0 flex-1 space-y-1">
            <div className="flex items-start justify-between gap-2">
              <h2 className="truncate text-base font-extrabold text-on-surface group-hover:text-primary">
                {character.name}
              </h2>
              {roleLabel && (
                <span
                  className={`shrink-0 rounded-full px-2.5 py-0.5 text-[11px] font-bold ${
                    character.role_tier === 'protagonist'
                      ? 'bg-primary text-on-primary'
                      : 'bg-surface-container-high text-on-surface-variant'
                  }`}
                >
                  {roleLabel}
                </span>
              )}
            </div>

            {character.aliases.length > 0 && (
              <p className="truncate text-xs text-on-surface-variant">
                {character.aliases.slice(0, 3).join(' · ')}
              </p>
            )}
          </div>
        </div>

        {character.description ? (
          <p className="line-clamp-2 text-xs leading-relaxed text-on-surface-variant">
            {character.description}
          </p>
        ) : (
          <div className="flex-1" />
        )}

        <div className="flex flex-wrap items-center justify-between gap-2 pt-1">
          <div className="flex flex-wrap gap-1.5">
            {delta && delta.newRevealsCount > 0 && (
              <span className="inline-flex items-center gap-1 rounded-full bg-tertiary px-2.5 py-0.5 text-[11px] font-bold text-on-tertiary">
                <Eye className="h-3 w-3" />
                {t.codex.revealBadge}
              </span>
            )}
            {delta?.isNew && (
              <span className="inline-flex items-center gap-1 rounded-full bg-tertiary-container px-2.5 py-0.5 text-[11px] font-bold text-on-tertiary-container">
                <Sparkles className="h-3 w-3" />
                {t.codex.newInChapter}
              </span>
            )}
            {delta && !delta.isNew && delta.newFactsCount > 0 && (
              <span className="inline-flex items-center rounded-full bg-primary-container px-2.5 py-0.5 text-[11px] font-bold text-on-primary-container">
                {formatFactCount(delta.newFactsCount, locale, true)}
              </span>
            )}
          </div>

          <span className="font-mono text-[11px] text-on-surface-variant">
            {formatFactCount(character.facts.length, locale)}
          </span>
        </div>
      </Link>
    </motion.div>
  );
}

export function CharactersPage() {
  const { t } = useI18n();
  const { bookId, bookDetails, activeChapter, prevChapter, nextChapter, goToChapter } =
    useBookContext();

  const codex = useCodex(bookId, activeChapter?.id ?? null, prevChapter?.id ?? null);
  const showSkeleton = useDelayedLoading(codex.loading && codex.characters.length === 0);
  const [query, setQuery] = useState('');
  const [roleFilter, setRoleFilter] = useState<RoleFilter>('all');
  const [onlyChanged, setOnlyChanged] = useState(false);

  const filtered = useMemo(
    () =>
      filterAndSortCharacters(codex.characters, {
        query,
        roleFilter,
        onlyChanged,
        deltaMap: codex.deltaMap,
      }),
    [codex.characters, codex.deltaMap, query, roleFilter, onlyChanged],
  );

  const changedCount = useMemo(
    () => codex.characters.filter((c) => codex.deltaMap[c.id]?.hasAnyChange).length,
    [codex.characters, codex.deltaMap],
  );

  const asOfQuery = activeChapter ? `?as_of=${encodeURIComponent(activeChapter.id)}` : '';
  const isFiltered = Boolean(query.trim() || roleFilter !== 'all' || onlyChanged);

  return (
    <div className="space-y-6">
      {/* Clean Header Strip with fixed-width ChapterStepper */}
      <div className="flex flex-col gap-3 rounded-[28px] border border-outline-variant/50 bg-surface-container p-4 sm:flex-row sm:items-center sm:justify-between sm:p-5">
        <div className="flex items-baseline gap-2.5">
          <h1 className="text-2xl font-extrabold tracking-tight">{t.tabs.codex}</h1>
          <span className="rounded-full bg-surface-container-highest px-2.5 py-0.5 font-mono text-xs font-bold text-on-surface-variant">
            {isFiltered
              ? `${filtered.length} / ${codex.characters.length}`
              : codex.characters.length}
          </span>
        </div>

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

      {/* Search & Expressive Filter Chips */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="relative w-full sm:max-w-xs">
          <Search className="pointer-events-none absolute left-4 top-1/2 h-4 w-4 -translate-y-1/2 text-on-surface-variant" />
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t.codex.searchPlaceholder}
            className="m3-color-transition h-11 w-full rounded-full bg-surface-container-high pl-11 pr-10 text-sm text-on-surface placeholder:text-on-surface-variant focus:bg-surface-container-lowest focus:outline-2 focus:outline-primary"
          />
          {query && (
            <button
              type="button"
              onClick={() => setQuery('')}
              className="absolute right-2 top-1/2 flex h-7 w-7 -translate-y-1/2 cursor-pointer items-center justify-center rounded-full text-on-surface-variant hover:bg-surface-container-highest"
            >
              <X className="h-4 w-4" />
            </button>
          )}
        </div>

        <div className="flex flex-wrap gap-1.5">
          {changedCount > 0 && (
            <motion.button
              type="button"
              whileTap={{ scale: 0.94, borderRadius: '10px' }}
              transition={M3_SPRINGS.spatial.fast}
              onClick={() => setOnlyChanged((prev) => !prev)}
              className={`m3-color-transition inline-flex h-9 cursor-pointer items-center gap-1.5 rounded-full px-3.5 text-xs font-bold ${
                onlyChanged
                  ? 'bg-tertiary text-on-tertiary'
                  : 'bg-tertiary-container text-on-tertiary-container'
              }`}
            >
              <Sparkles className="h-3.5 w-3.5" />
              <span>{t.codex.filterChanged}</span>
              <span className="font-mono opacity-85">({changedCount})</span>
            </motion.button>
          )}

          {ROLE_FILTERS.map((role) => {
            const active = roleFilter === role;
            const label = role === 'all' ? t.codex.filterAll : t.codex.roles[role];
            return (
              <motion.button
                key={role}
                type="button"
                whileTap={{ scale: 0.94, borderRadius: '10px' }}
                transition={M3_SPRINGS.spatial.fast}
                onClick={() => setRoleFilter(role)}
                className={`m3-color-transition h-9 cursor-pointer rounded-full px-3.5 text-xs font-bold ${
                  active
                    ? 'bg-primary text-on-primary'
                    : 'bg-surface-container-high text-on-surface-variant hover:bg-surface-container-highest'
                }`}
              >
                {label}
              </motion.button>
            );
          })}
        </div>
      </div>

      {/* Equal-Height Full-Width Grid */}
      {showSkeleton ? (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {[1, 2, 3, 4, 5, 6].map((n) => (
            <div key={n} className="h-40 animate-pulse rounded-[28px] bg-surface-container" />
          ))}
        </div>
      ) : codex.loading && codex.characters.length === 0 ? (
        <div className="min-h-[40vh]" />
      ) : codex.error ? (
        <div className="rounded-[28px] bg-error-container p-6 text-sm font-medium text-on-error-container">
          {t.codex.error} ({codex.error})
        </div>
      ) : codex.characters.length === 0 ? (
        <div className="space-y-2 rounded-[28px] bg-surface-container-low p-10 text-center">
          <Users className="mx-auto h-8 w-8 text-on-surface-variant/60" />
          <p className="text-sm text-on-surface-variant">{t.codex.empty}</p>
        </div>
      ) : filtered.length === 0 ? (
        <div className="rounded-[28px] bg-surface-container-low p-10 text-center text-sm text-on-surface-variant">
          {t.codex.emptySearch}
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {filtered.map((char) => (
            <CharacterGridCard
              key={char.id}
              bookId={bookId}
              character={char}
              delta={codex.deltaMap[char.id]}
              asOfQuery={asOfQuery}
            />
          ))}
        </div>
      )}
    </div>
  );
}
