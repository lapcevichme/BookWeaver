import type { ChapterInfo, VolumeGroup } from '../types/book';
import { cleanChapterTitle } from './formatting';

export function formatChapterNumber(num: number): string {
  return Number.isInteger(num) ? String(num) : String(num);
}

export function groupChaptersByVolume(chapters: ChapterInfo[]): VolumeGroup[] {
  const byVolume = new Map<number, ChapterInfo[]>();
  for (const chapter of chapters) {
    const vol = chapter.volume_num || 1;
    const list = byVolume.get(vol);
    if (list) {
      list.push(chapter);
    } else {
      byVolume.set(vol, [chapter]);
    }
  }

  return Array.from(byVolume.entries())
    .sort(([a], [b]) => a - b)
    .map(([volumeNum, items]) => ({ volumeNum, chapters: items }));
}

export interface ChapterPickerBucket {
  key: string;
  volumeNum: number;
  rangeLabel: string | null;
  chapters: ChapterInfo[];
}

/**
 * Splits chapters into collapsible buckets by volume, and further chunks any single
 * giant volume (> chunkSize chapters, common in 3000+ chapter webnovels) into fixed ranges.
 */
export function buildChapterBuckets(
  chapters: ChapterInfo[],
  chunkSize = 100,
): ChapterPickerBucket[] {
  const volumes = groupChaptersByVolume(chapters);
  const buckets: ChapterPickerBucket[] = [];

  for (const vol of volumes) {
    if (vol.chapters.length <= chunkSize) {
      buckets.push({
        key: `vol-${vol.volumeNum}`,
        volumeNum: vol.volumeNum,
        rangeLabel: null,
        chapters: vol.chapters,
      });
      continue;
    }

    for (let start = 0; start < vol.chapters.length; start += chunkSize) {
      const slice = vol.chapters.slice(start, start + chunkSize);
      const firstNum = formatChapterNumber(slice[0].chapter_num);
      const lastNum = formatChapterNumber(slice[slice.length - 1].chapter_num);
      buckets.push({
        key: `vol-${vol.volumeNum}-${start}`,
        volumeNum: vol.volumeNum,
        rangeLabel: `${firstNum}–${lastNum}`,
        chapters: slice,
      });
    }
  }

  return buckets;
}

/**
 * Fast chapter search supporting exact chapter number ("2349"), volume.chapter ("1.15" or "1 15"),
 * and substring match by title or ID. Exact chapter number matches always rank first.
 */
export function filterChaptersByQuery(
  chapters: ChapterInfo[],
  rawQuery: string,
  limit = 60,
): ChapterInfo[] {
  const query = rawQuery.trim().toLowerCase();
  if (!query) return chapters.slice(0, limit);

  const volChapMatch = query.match(/^(\d+)[.\s:/-]+([\d.]+)$/);
  const exactNum = /^\d+(?:\.\d+)?$/.test(query) ? Number(query) : null;

  const exactMatches: ChapterInfo[] = [];
  const partialMatches: ChapterInfo[] = [];

  for (const ch of chapters) {
    if (volChapMatch) {
      const targetVol = Number(volChapMatch[1]);
      const targetChap = Number(volChapMatch[2]);
      if (ch.volume_num === targetVol && ch.chapter_num === targetChap) {
        exactMatches.push(ch);
        continue;
      }
    }

    if (exactNum !== null && ch.chapter_num === exactNum) {
      exactMatches.push(ch);
      continue;
    }

    const numStr = formatChapterNumber(ch.chapter_num);
    const titleStr = cleanChapterTitle(ch.title).toLowerCase();
    if (
      numStr.includes(query) ||
      titleStr.includes(query) ||
      ch.id.toLowerCase().includes(query)
    ) {
      partialMatches.push(ch);
    }
  }

  return [...exactMatches, ...partialMatches].slice(0, limit);
}

export function findChapterIndex(chapters: ChapterInfo[], chapterId: string | null): number {
  if (!chapterId) return -1;
  return chapters.findIndex((c) => c.id === chapterId);
}

export function resolveActiveChapter(
  chapters: ChapterInfo[],
  savedChapterId: string | null,
): ChapterInfo | null {
  if (chapters.length === 0) return null;
  if (savedChapterId) {
    const found = chapters.find((c) => c.id === savedChapterId);
    if (found) return found;
  }
  return chapters[0];
}

export function getAdjacentChapter(
  chapters: ChapterInfo[],
  currentChapterId: string | null,
  delta: -1 | 1,
): ChapterInfo | null {
  const idx = findChapterIndex(chapters, currentChapterId);
  if (idx === -1) return null;
  const nextIdx = idx + delta;
  if (nextIdx < 0 || nextIdx >= chapters.length) return null;
  return chapters[nextIdx];
}

export function getReadingProgressPercent(
  chapters: ChapterInfo[],
  currentChapterId: string | null,
): number {
  if (chapters.length === 0) return 0;
  const idx = findChapterIndex(chapters, currentChapterId);
  if (idx === -1) return 0;
  return Math.round(((idx + 1) / chapters.length) * 100);
}
