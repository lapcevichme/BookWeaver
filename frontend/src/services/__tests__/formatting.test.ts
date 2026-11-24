import { describe, expect, it } from 'vitest';
import {
  cleanChapterTitle,
  formatFactCount,
  formatSavedBookmarkLabel,
  parseChapterBlocks,
} from '../formatting';

describe('formatting helpers', () => {
  it('pluralizes Russian and English fact counts correctly', () => {
    expect(formatFactCount(1, 'ru')).toBe('1 факт');
    expect(formatFactCount(2, 'ru')).toBe('2 факта');
    expect(formatFactCount(5, 'ru')).toBe('5 фактов');
    expect(formatFactCount(11, 'ru')).toBe('11 фактов');
    expect(formatFactCount(21, 'ru')).toBe('21 факт');
    expect(formatFactCount(50, 'ru')).toBe('50 фактов');
    expect(formatFactCount(8, 'ru', true)).toBe('+8 фактов');
    expect(formatFactCount(1, 'en')).toBe('1 fact');
    expect(formatFactCount(5, 'en')).toBe('5 facts');
  });

  it('strips redundant volume/chapter prefixes from chapter titles', () => {
    expect(cleanChapterTitle('Том 1 Глава 15 - Тайные операции')).toBe('Тайные операции');
    expect(cleanChapterTitle('# Том 1 Глава 6 - Дегустатор')).toBe('Дегустатор');
    expect(cleanChapterTitle('Глава 3: Прибытие')).toBe('Прибытие');
    expect(cleanChapterTitle('Пролог')).toBe('Пролог');
  });

  it('formats saved chapter slugs into readable labels', () => {
    expect(formatSavedBookmarkLabel('vol_1_chap_15', 'Т.', 'Гл.')).toBe('Т. 1 · Гл. 15');
    expect(formatSavedBookmarkLabel('vol_2_chap_0.5', 'Vol.', 'Ch.')).toBe('Vol. 2 · Ch. 0.5');
  });

  it('parses markdown chapter text into clean reader blocks', () => {
    const raw = [
      '# Том 1 Глава 15 - Тайные операции',
      'Первый абзац с **выделением**.',
      '\\*\\*\\*',
      '![Иллюстрация](../images/img_123.jpg)',
      '.',
      'Второй абзац.',
    ].join('\n\n');

    const blocks = parseChapterBlocks(raw, 'Том 1 Глава 15 - Тайные операции');
    expect(blocks).toEqual([
      { type: 'paragraph', text: 'Первый абзац с выделением.' },
      { type: 'sceneBreak' },
      { type: 'illustration', alt: 'Иллюстрация', filename: 'img_123.jpg' },
      { type: 'paragraph', text: 'Второй абзац.' },
    ]);
  });
});
