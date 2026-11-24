import type { BookDetails, ChapterText, ProjectCatalogItem } from '../types/book';
import type { BookSummaries, CharacterAsOf } from '../types/knowledge';

const API_BASE = '/api/v1';

// Simple in-memory caches so tab/chapter switching is instant on revisit
let catalogCache: ProjectCatalogItem[] | null = null;
const bookDetailsCache = new Map<string, BookDetails>();
const chapterTextCache = new Map<string, ChapterText>();
const charactersAsOfCache = new Map<string, CharacterAsOf[]>();
const summariesAsOfCache = new Map<string, BookSummaries>();

export class ApiError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function requestJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: { Accept: 'application/json' },
    signal,
  });

  if (!response.ok) {
    const text = await response.text().catch(() => '');
    throw new ApiError(response.status, text || `HTTP ${response.status}`);
  }

  return (await response.json()) as T;
}

export function getCachedProjectsCatalog(): ProjectCatalogItem[] | null {
  return catalogCache;
}

export function getCachedBookDetails(bookId: string): BookDetails | null {
  return bookDetailsCache.get(bookId) ?? null;
}

export function getCachedChapterText(bookId: string, chapterId: string): ChapterText | null {
  return chapterTextCache.get(`${bookId}::${chapterId}`) ?? null;
}

export function getCachedCharactersAsOf(
  bookId: string,
  chapterId: string,
): CharacterAsOf[] | null {
  return charactersAsOfCache.get(`${bookId}::${chapterId}`) ?? null;
}

export function getCachedSummariesAsOf(bookId: string, chapterId: string): BookSummaries | null {
  return summariesAsOfCache.get(`${bookId}::${chapterId}`) ?? null;
}

export function fetchProjects(signal?: AbortSignal): Promise<string[]> {
  return requestJson<string[]>('/projects/', signal);
}

export async function fetchProjectsCatalog(
  signal?: AbortSignal,
  forceRefresh = false,
): Promise<ProjectCatalogItem[]> {
  if (catalogCache && !forceRefresh) return catalogCache;
  try {
    const data = await requestJson<ProjectCatalogItem[]>('/projects/catalog', signal);
    catalogCache = data;
    return data;
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err;
    const ids = await fetchProjects(signal);
    const fallback = ids.map((id) => ({
      id,
      title: id,
      author: null,
      chapters_count: 0,
      volumes_count: 0,
      has_cover: false,
    }));
    catalogCache = fallback;
    return fallback;
  }
}

export async function fetchBookDetails(
  bookId: string,
  signal?: AbortSignal,
  forceRefresh = false,
): Promise<BookDetails> {
  const cached = bookDetailsCache.get(bookId);
  if (cached && !forceRefresh) return cached;
  const data = await requestJson<BookDetails>(`/projects/${encodeURIComponent(bookId)}`, signal);
  bookDetailsCache.set(bookId, data);
  return data;
}

export async function fetchChapterText(
  bookId: string,
  chapterId: string,
  signal?: AbortSignal,
): Promise<ChapterText> {
  const key = `${bookId}::${chapterId}`;
  const cached = chapterTextCache.get(key);
  if (cached) return cached;
  const data = await requestJson<ChapterText>(
    `/projects/${encodeURIComponent(bookId)}/chapters/${encodeURIComponent(chapterId)}/text`,
    signal,
  );
  chapterTextCache.set(key, data);
  return data;
}

export async function fetchCharactersAsOf(
  bookId: string,
  chapterId: string,
  signal?: AbortSignal,
): Promise<CharacterAsOf[]> {
  const key = `${bookId}::${chapterId}`;
  const cached = charactersAsOfCache.get(key);
  if (cached) return cached;
  const params = new URLSearchParams({ chapter: chapterId });
  const data = await requestJson<CharacterAsOf[]>(
    `/projects/${encodeURIComponent(bookId)}/characters/as-of?${params.toString()}`,
    signal,
  );
  charactersAsOfCache.set(key, data);
  return data;
}

export async function fetchSummariesAsOf(
  bookId: string,
  chapterId: string,
  signal?: AbortSignal,
): Promise<BookSummaries> {
  const key = `${bookId}::${chapterId}`;
  const cached = summariesAsOfCache.get(key);
  if (cached) return cached;
  const params = new URLSearchParams({ as_of: chapterId });
  const data = await requestJson<BookSummaries>(
    `/projects/${encodeURIComponent(bookId)}/summaries?${params.toString()}`,
    signal,
  );
  summariesAsOfCache.set(key, data);
  return data;
}

export function getBookCoverUrl(bookId: string): string {
  return `${API_BASE}/projects/${encodeURIComponent(bookId)}/cover`;
}

export function getCharacterPortraitUrl(bookId: string, charId: string): string {
  return `${API_BASE}/projects/${encodeURIComponent(bookId)}/characters/${encodeURIComponent(charId)}/portrait`;
}

export function getBookImageUrl(bookId: string, filename: string): string {
  return `${API_BASE}/projects/${encodeURIComponent(bookId)}/images/${encodeURIComponent(filename)}`;
}
