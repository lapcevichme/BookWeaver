const SCROLL_KEY = 'bw_chapter_scroll';
const MAX_ENTRIES = 60;

interface ScrollEntry {
  ratio: number;
  updatedAt: number;
}

type ScrollMap = Record<string, ScrollEntry>;

function readMap(): ScrollMap {
  try {
    const raw = localStorage.getItem(SCROLL_KEY);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as ScrollMap;
    }
  } catch {
    // Ignore corrupted storage
  }
  return {};
}

function makeKey(bookId: string, chapterId: string): string {
  return `${bookId}::${chapterId}`;
}

export function readChapterScrollRatio(bookId: string, chapterId: string): number {
  const entry = readMap()[makeKey(bookId, chapterId)];
  if (!entry || typeof entry.ratio !== 'number') return 0;
  return Math.max(0, Math.min(1, entry.ratio));
}

export function saveChapterScrollRatio(bookId: string, chapterId: string, ratio: number): void {
  const clamped = Math.max(0, Math.min(1, ratio));
  try {
    const map = readMap();
    map[makeKey(bookId, chapterId)] = { ratio: clamped, updatedAt: Date.now() };

    const keys = Object.keys(map);
    if (keys.length > MAX_ENTRIES) {
      keys
        .sort((a, b) => (map[a]?.updatedAt ?? 0) - (map[b]?.updatedAt ?? 0))
        .slice(0, keys.length - MAX_ENTRIES)
        .forEach((oldKey) => {
          delete map[oldKey];
        });
    }

    localStorage.setItem(SCROLL_KEY, JSON.stringify(map));
  } catch {
    // Ignore storage errors
  }
}
