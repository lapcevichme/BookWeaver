import type { CharacterAsOf } from '../types/knowledge';

export interface TextSegment {
  text: string;
  characterId?: string;
}

export interface HighlightMatcher {
  regex: RegExp | null;
  resolveCharacterId: (matchedText: string) => string | undefined;
}

const ROLE_PRIORITY: Record<string, number> = {
  protagonist: 0,
  major: 1,
  minor: 2,
  background: 3,
};

const WORD_CHAR_RE = /[\p{L}\p{N}_]/u;

function escapeRegExp(str: string): string {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Generates a regex fragment for a character name/alias supporting common Russian case endings
 * on the last word (when >= 4 Cyrillic letters) while keeping short/indeclinable names exact.
 */
function buildTermPattern(term: string): string {
  const words = term.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return '';

  const parts = words.map((word, index) => {
    const isLast = index === words.length - 1;
    const isCyrillic = /^[а-яёА-ЯЁ-]+$/.test(word);
    if (!isLast || !isCyrillic || word.length < 4) {
      return escapeRegExp(word);
    }

    const lower = word.toLowerCase();
    // Indeclinable endings (Маомао, Гёкуё, Джинши, Лишу)
    if (/[оеиуюё]$/.test(lower)) {
      return escapeRegExp(word);
    }

    // Feminine/masculine -а / -я endings (Рифа -> Рифы/Рифе/Рифу/Рифой)
    if (/[ая]$/.test(lower)) {
      const stem = escapeRegExp(word.slice(0, -1));
      return `${stem}(?:[аяыиеую]|ой|ей|ою|ею)`;
    }

    // Consonant / -й / -ь endings (Гаошун -> Гаошуна/Гаошуну/Гаошуном/Гаошуне)
    if (/[йь]$/.test(lower)) {
      const stem = escapeRegExp(word.slice(0, -1));
      return `${stem}(?:[йьяюеи]|ем|ём|ям)`;
    }

    return `${escapeRegExp(word)}(?:[ауеыияю]|ом|ем)?`;
  });

  return parts.join('\\s+');
}

export function createHighlightMatcher(characters: CharacterAsOf[]): HighlightMatcher {
  const sortedChars = [...characters].sort((a, b) => {
    const pA = ROLE_PRIORITY[a.role_tier ?? 'background'] ?? 4;
    const pB = ROLE_PRIORITY[b.role_tier ?? 'background'] ?? 4;
    return pA - pB;
  });

  const termEntries: { term: string; pattern: string; charId: string }[] = [];
  const seenTerms = new Set<string>();

  for (const char of sortedChars) {
    const candidates = [char.name, ...char.aliases];
    for (const raw of candidates) {
      const term = raw.trim();
      if (term.length < 3) continue;
      const key = term.toLowerCase();
      if (seenTerms.has(key)) continue;
      seenTerms.add(key);

      const pattern = buildTermPattern(term);
      if (pattern) {
        termEntries.push({ term, pattern, charId: char.id });
      }
    }
  }

  if (termEntries.length === 0) {
    return { regex: null, resolveCharacterId: () => undefined };
  }

  // Longer terms first so multi-word titles/names match before shorter substrings
  termEntries.sort((a, b) => b.term.length - a.term.length);

  const combined = new RegExp(
    termEntries.map((entry) => `(${entry.pattern})`).join('|'),
    'giu',
  );

  // Build fast lookup per entry regex for exact attribution
  const individualMatchers = termEntries.map((entry) => ({
    regex: new RegExp(`^(?:${entry.pattern})$`, 'iu'),
    charId: entry.charId,
  }));

  const cache = new Map<string, string | undefined>();
  const resolveCharacterId = (matchedText: string): string | undefined => {
    const key = matchedText.toLowerCase();
    if (cache.has(key)) return cache.get(key);
    for (const item of individualMatchers) {
      if (item.regex.test(matchedText)) {
        cache.set(key, item.charId);
        return item.charId;
      }
    }
    cache.set(key, undefined);
    return undefined;
  };

  return { regex: combined, resolveCharacterId };
}

export function highlightParagraph(text: string, matcher: HighlightMatcher): TextSegment[] {
  if (!matcher.regex || !text) {
    return [{ text }];
  }

  const segments: TextSegment[] = [];
  const regex = new RegExp(matcher.regex.source, matcher.regex.flags);
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = regex.exec(text)) !== null) {
    const start = match.index;
    const matchedText = match[0];
    const end = start + matchedText.length;

    const prevChar = start > 0 ? text[start - 1] : '';
    const nextChar = end < text.length ? text[end] : '';
    if ((prevChar && WORD_CHAR_RE.test(prevChar)) || (nextChar && WORD_CHAR_RE.test(nextChar))) {
      continue;
    }

    const characterId = matcher.resolveCharacterId(matchedText);
    if (!characterId) continue;

    if (start > lastIndex) {
      segments.push({ text: text.slice(lastIndex, start) });
    }
    segments.push({ text: matchedText, characterId });
    lastIndex = end;
  }

  if (lastIndex === 0) {
    return [{ text }];
  }

  if (lastIndex < text.length) {
    segments.push({ text: text.slice(lastIndex) });
  }

  return segments;
}
