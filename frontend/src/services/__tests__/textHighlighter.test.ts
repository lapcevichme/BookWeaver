import { describe, expect, it } from 'vitest';
import { createHighlightMatcher, highlightParagraph } from '../textHighlighter';
import type { CharacterAsOf } from '../../types/knowledge';

function makeChar(id: string, name: string, aliases: string[] = []): CharacterAsOf {
  return {
    id,
    name,
    aliases,
    description: '',
    description_from: null,
    first_chapter: 'vol_1_chap_1',
    role_tier: 'major',
    entity_type: 'person',
    gender: null,
    voice_base: null,
    voice_profile: '',
    visual_base: '',
    chapter_mentions: {},
    facts: [],
    reveals: [],
  };
}

describe('textHighlighter', () => {
  it('highlights indeclinable names, declined Russian names, and aliases without partial word leaks', () => {
    const matcher = createHighlightMatcher([
      makeChar('c1', 'Маомао', ['Сяомао']),
      makeChar('c2', 'Гаошун'),
      makeChar('c3', 'Рифа', ['госпожа Рифа']),
    ]);

    const segments = highlightParagraph(
      'Маомао поговорила с Гаошуном, а затем взглянула на госпожу Рифу.',
      matcher,
    );

    const hits = segments.filter((s) => s.characterId).map((s) => [s.text, s.characterId]);
    expect(hits).toEqual([
      ['Маомао', 'c1'],
      ['Гаошуном', 'c2'],
      ['Рифу', 'c3'],
    ]);
  });

  it('does not match inside larger unrelated words', () => {
    const matcher = createHighlightMatcher([makeChar('c1', 'Рифа')]);
    const segments = highlightParagraph('Это был тарифный план.', matcher);
    expect(segments).toEqual([{ text: 'Это был тарифный план.' }]);
  });
});
