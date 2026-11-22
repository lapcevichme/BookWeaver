import type { ChapterInfo, VolumeGroup } from '../types/book';

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
