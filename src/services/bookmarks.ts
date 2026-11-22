const BOOKMARKS_KEY = 'bw_bookmarks';

type BookmarkMap = Record<string, string>;

function readMap(): BookmarkMap {
  try {
    const raw = localStorage.getItem(BOOKMARKS_KEY);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as BookmarkMap;
    }
  } catch {
    // Ignore storage errors
  }
  return {};
}

export function readSavedBookmark(bookId: string): string | null {
  return readMap()[bookId] ?? null;
}

export function saveBookmark(bookId: string, chapterId: string): void {
  try {
    const next = { ...readMap(), [bookId]: chapterId };
    localStorage.setItem(BOOKMARKS_KEY, JSON.stringify(next));
  } catch {
    // Ignore storage errors
  }
}
