import type { BookDetails, ChapterText, ProjectCatalogItem } from '../types/book';
import type { BookSummaries, CharacterAsOf } from '../types/knowledge';

const API_BASE = '/api/v1';

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

export function fetchProjects(signal?: AbortSignal): Promise<string[]> {
  return requestJson<string[]>('/projects/', signal);
}

export async function fetchProjectsCatalog(signal?: AbortSignal): Promise<ProjectCatalogItem[]> {
  try {
    return await requestJson<ProjectCatalogItem[]>('/projects/catalog', signal);
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err;
    // Fallback if backend hasn't been restarted yet to pick up /catalog
    const ids = await fetchProjects(signal);
    return ids.map((id) => ({
      id,
      title: id,
      author: null,
      chapters_count: 0,
      volumes_count: 0,
      has_cover: false,
    }));
  }
}

export function fetchBookDetails(bookId: string, signal?: AbortSignal): Promise<BookDetails> {
  return requestJson<BookDetails>(`/projects/${encodeURIComponent(bookId)}`, signal);
}

export function fetchChapterText(
  bookId: string,
  chapterId: string,
  signal?: AbortSignal,
): Promise<ChapterText> {
  return requestJson<ChapterText>(
    `/projects/${encodeURIComponent(bookId)}/chapters/${encodeURIComponent(chapterId)}/text`,
    signal,
  );
}

export function fetchCharactersAsOf(
  bookId: string,
  chapterId: string,
  signal?: AbortSignal,
): Promise<CharacterAsOf[]> {
  const params = new URLSearchParams({ chapter: chapterId });
  return requestJson<CharacterAsOf[]>(
    `/projects/${encodeURIComponent(bookId)}/characters/as-of?${params.toString()}`,
    signal,
  );
}

export function fetchSummariesAsOf(
  bookId: string,
  chapterId: string,
  signal?: AbortSignal,
): Promise<BookSummaries> {
  const params = new URLSearchParams({ as_of: chapterId });
  return requestJson<BookSummaries>(
    `/projects/${encodeURIComponent(bookId)}/summaries?${params.toString()}`,
    signal,
  );
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
