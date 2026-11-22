import type { ChapterInfo } from '../types/book';
import type { CharacterAsOf, CharacterDelta, RoleTier } from '../types/knowledge';
import { formatChapterNumber } from './chapters';

const ROLE_WEIGHT: Record<string, number> = {
  protagonist: 0,
  major: 1,
  minor: 2,
  background: 3,
};

export function computeChapterDelta(
  currentChars: CharacterAsOf[],
  prevChars: CharacterAsOf[] | null,
  currentChapterId: string | null,
): Record<string, CharacterDelta> {
  const prevById = new Map<string, CharacterAsOf>();
  if (prevChars) {
    for (const char of prevChars) {
      prevById.set(char.id, char);
    }
  }

  const result: Record<string, CharacterDelta> = {};
  for (const char of currentChars) {
    const prev = prevById.get(char.id);
    const isNew = prevChars ? !prev : char.first_chapter === currentChapterId;

    const newFactsCount = currentChapterId
      ? char.facts.filter((f) => f.chapter === currentChapterId).length
      : 0;

    const prevAliasSet = new Set(prev?.aliases ?? []);
    const newAliases = prev
      ? char.aliases.filter((alias) => !prevAliasSet.has(alias))
      : [];

    const newRevealsCount = currentChapterId
      ? char.reveals.filter((r) => r.revealed_in === currentChapterId).length
      : 0;

    const hasMentionNow = Boolean(currentChapterId && char.chapter_mentions[currentChapterId]);
    const hasAnyChange =
      isNew || newFactsCount > 0 || newAliases.length > 0 || newRevealsCount > 0 || hasMentionNow;

    result[char.id] = {
      isNew,
      newFactsCount,
      newAliases,
      newRevealsCount,
      hasAnyChange,
    };
  }

  return result;
}

export type RoleFilter = 'all' | RoleTier;

export function filterAndSortCharacters(
  characters: CharacterAsOf[],
  options: {
    query: string;
    roleFilter: RoleFilter;
    onlyChanged: boolean;
    deltaMap: Record<string, CharacterDelta>;
  },
): CharacterAsOf[] {
  const normalized = options.query.trim().toLowerCase();

  const filtered = characters.filter((char) => {
    if (options.roleFilter !== 'all' && char.role_tier !== options.roleFilter) {
      return false;
    }

    if (options.onlyChanged) {
      const delta = options.deltaMap[char.id];
      if (!delta?.hasAnyChange) return false;
    }

    if (normalized) {
      const matchesName = char.name.toLowerCase().includes(normalized);
      const matchesAlias = char.aliases.some((alias) => alias.toLowerCase().includes(normalized));
      if (!matchesName && !matchesAlias) return false;
    }

    return true;
  });

  return [...filtered].sort((a, b) => {
    const deltaA = options.deltaMap[a.id];
    const deltaB = options.deltaMap[b.id];

    const revealDiff = (deltaB?.newRevealsCount ?? 0) - (deltaA?.newRevealsCount ?? 0);
    if (revealDiff !== 0) return revealDiff;

    const roleA = ROLE_WEIGHT[a.role_tier ?? 'background'] ?? 4;
    const roleB = ROLE_WEIGHT[b.role_tier ?? 'background'] ?? 4;
    if (roleA !== roleB) return roleA - roleB;

    const mentionsDiff =
      Object.keys(b.chapter_mentions).length - Object.keys(a.chapter_mentions).length;
    if (mentionsDiff !== 0) return mentionsDiff;

    return a.name.localeCompare(b.name);
  });
}

export function formatChapterRef(
  chapterId: string | null,
  chaptersById: Map<string, ChapterInfo>,
  volShort: string,
  chapShort: string,
): string {
  if (!chapterId) return '';
  const ch = chaptersById.get(chapterId);
  if (ch) {
    return `${volShort} ${ch.volume_num} · ${chapShort} ${formatChapterNumber(ch.chapter_num)}`;
  }
  const match = chapterId.match(/vol_(\d+)_chap_([\d.]+)/);
  if (match) {
    return `${volShort} ${match[1]} · ${chapShort} ${match[2]}`;
  }
  return chapterId;
}
