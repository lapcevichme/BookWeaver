import { describe, expect, it } from 'vitest';
import {
  buildChapterBuckets,
  filterChaptersByQuery,
  formatChapterNumber,
  getAdjacentChapter,
  getReadingProgressPercent,
  groupChaptersByVolume,
  resolveActiveChapter,
} from '../chapters';
import type { ChapterInfo } from '../../types/book';

const SAMPLE_CHAPTERS: ChapterInfo[] = [
  { id: 'vol_1_chap_0.5', volume_num: 1, chapter_num: 0.5, title: 'Пролог', status: 'processed', has_audio: false },
  { id: 'vol_1_chap_1', volume_num: 1, chapter_num: 1, title: 'Глава 1', status: 'processed', has_audio: false },
  { id: 'vol_1_chap_2', volume_num: 1, chapter_num: 2, title: 'Глава 2', status: 'processed', has_audio: false },
  { id: 'vol_2_chap_1', volume_num: 2, chapter_num: 1, title: 'Том 2 Глава 1', status: 'processed', has_audio: false },
];

describe('chapters helpers', () => {
  it('groups chapters by volume in ascending volume order', () => {
    const shuffled = [SAMPLE_CHAPTERS[3], SAMPLE_CHAPTERS[0], SAMPLE_CHAPTERS[1]];
    const grouped = groupChaptersByVolume(shuffled);
    expect(grouped.map((g) => g.volumeNum)).toEqual([1, 2]);
    expect(grouped[0].chapters.map((c) => c.id)).toEqual(['vol_1_chap_0.5', 'vol_1_chap_1']);
    expect(grouped[1].chapters.map((c) => c.id)).toEqual(['vol_2_chap_1']);
  });

  it('resolves saved chapter or falls back to the first chapter', () => {
    expect(resolveActiveChapter(SAMPLE_CHAPTERS, 'vol_1_chap_2')?.id).toBe('vol_1_chap_2');
    expect(resolveActiveChapter(SAMPLE_CHAPTERS, 'missing_id')?.id).toBe('vol_1_chap_0.5');
    expect(resolveActiveChapter(SAMPLE_CHAPTERS, null)?.id).toBe('vol_1_chap_0.5');
    expect(resolveActiveChapter([], 'vol_1_chap_1')).toBeNull();
  });

  it('computes adjacent chapters safely at boundaries', () => {
    expect(getAdjacentChapter(SAMPLE_CHAPTERS, 'vol_1_chap_0.5', -1)).toBeNull();
    expect(getAdjacentChapter(SAMPLE_CHAPTERS, 'vol_1_chap_0.5', 1)?.id).toBe('vol_1_chap_1');
    expect(getAdjacentChapter(SAMPLE_CHAPTERS, 'vol_2_chap_1', 1)).toBeNull();
    expect(getAdjacentChapter(SAMPLE_CHAPTERS, 'vol_2_chap_1', -1)?.id).toBe('vol_1_chap_2');
  });

  it('calculates reading progress percentage accurately', () => {
    expect(getReadingProgressPercent([], null)).toBe(0);
    expect(getReadingProgressPercent(SAMPLE_CHAPTERS, 'vol_1_chap_0.5')).toBe(25);
    expect(getReadingProgressPercent(SAMPLE_CHAPTERS, 'vol_1_chap_1')).toBe(50);
    expect(getReadingProgressPercent(SAMPLE_CHAPTERS, 'vol_2_chap_1')).toBe(100);
  });

  it('formats integer and fractional chapter numbers', () => {
    expect(formatChapterNumber(1)).toBe('1');
    expect(formatChapterNumber(0.5)).toBe('0.5');
  });

  it('chunks giant single-volume books (3200 chapters) into 100-chapter buckets', () => {
    const giantBook: ChapterInfo[] = Array.from({ length: 3200 }, (_, i) => ({
      id: `vol_1_chap_${i + 1}`,
      volume_num: 1,
      chapter_num: i + 1,
      title: `Глава ${i + 1}`,
      status: 'processed',
      has_audio: false,
    }));

    const buckets = buildChapterBuckets(giantBook, 100);
    expect(buckets).toHaveLength(32);
    expect(buckets[0].rangeLabel).toBe('1–100');
    expect(buckets[23].rangeLabel).toBe('2301–2400');

    const matches = filterChaptersByQuery(giantBook, '2349');
    expect(matches[0].id).toBe('vol_1_chap_2349');
  });

  it('matches volume.chapter queries like "2.1"', () => {
    const matches = filterChaptersByQuery(SAMPLE_CHAPTERS, '2.1');
    expect(matches[0].id).toBe('vol_2_chap_1');
  });
});
