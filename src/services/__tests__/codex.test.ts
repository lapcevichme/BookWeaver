import { describe, expect, it } from 'vitest';
import { computeChapterDelta, filterAndSortCharacters } from '../codex';
import type { CharacterAsOf } from '../../types/knowledge';

function makeChar(overrides: Partial<CharacterAsOf>): CharacterAsOf {
  return {
    id: 'c1',
    name: 'Маомао',
    aliases: [],
    description: 'Аптекарь',
    description_from: 'vol_1_chap_1',
    first_chapter: 'vol_1_chap_1',
    role_tier: 'protagonist',
    entity_type: 'person',
    gender: 'female',
    voice_base: null,
    voice_profile: '',
    visual_base: '',
    chapter_mentions: { vol_1_chap_1: 'Похищена во дворец' },
    facts: [],
    reveals: [],
    ...overrides,
  };
}

describe('codex helpers', () => {
  it('detects new characters, new facts, new aliases and reveals between chapters', () => {
    const prev = [
      makeChar({
        id: 'c1',
        name: 'Маомао',
        aliases: ['Травница'],
        facts: [{ id: 'f1', fact: 'Любит яды', kind: 'quirk', chapter: 'vol_1_chap_1', until: null }],
      }),
    ];

    const current = [
      makeChar({
        id: 'c1',
        name: 'Маомао',
        aliases: ['Травница', 'Сяомао'],
        facts: [
          { id: 'f1', fact: 'Любит яды', kind: 'quirk', chapter: 'vol_1_chap_1', until: null },
          { id: 'f2', fact: 'Стала дегустатором', kind: 'status', chapter: 'vol_1_chap_2', until: null },
        ],
      }),
      makeChar({
        id: 'c2',
        name: 'Джинши',
        role_tier: 'major',
        first_chapter: 'vol_1_chap_2',
        chapter_mentions: { vol_1_chap_2: 'Замечает Маомао' },
      }),
    ];

    const delta = computeChapterDelta(current, prev, 'vol_1_chap_2');
    expect(delta.c1.isNew).toBe(false);
    expect(delta.c1.newFactsCount).toBe(1);
    expect(delta.c1.newAliases).toEqual(['Сяомао']);
    expect(delta.c1.hasAnyChange).toBe(true);

    expect(delta.c2.isNew).toBe(true);
    expect(delta.c2.hasAnyChange).toBe(true);
  });

  it('filters by known aliases and role without leaking unmatched entries', () => {
    const chars = [
      makeChar({ id: 'c1', name: 'Маомао', aliases: ['Сяомао'], role_tier: 'protagonist' }),
      makeChar({ id: 'c2', name: 'Джинши', aliases: ['Красавец-евнух'], role_tier: 'major' }),
    ];
    const deltaMap = computeChapterDelta(chars, null, 'vol_1_chap_1');

    const byAlias = filterAndSortCharacters(chars, {
      query: 'евнух',
      roleFilter: 'all',
      onlyChanged: false,
      deltaMap,
    });
    expect(byAlias.map((c) => c.id)).toEqual(['c2']);

    const byRole = filterAndSortCharacters(chars, {
      query: '',
      roleFilter: 'protagonist',
      onlyChanged: false,
      deltaMap,
    });
    expect(byRole.map((c) => c.id)).toEqual(['c1']);
  });
});
