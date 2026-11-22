import { useEffect, useMemo, useState } from 'react';
import { fetchCharactersAsOf } from '../services/api';
import { computeChapterDelta } from '../services/codex';
import type { CharacterAsOf, CharacterDelta } from '../types/knowledge';

export function useCodex(
  bookId: string | null,
  activeChapterId: string | null,
  prevChapterId: string | null,
) {
  const [characters, setCharacters] = useState<CharacterAsOf[]>([]);
  const [prevCharacters, setPrevCharacters] = useState<CharacterAsOf[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!bookId || !activeChapterId) {
      setCharacters([]);
      setPrevCharacters(null);
      return;
    }

    const controller = new AbortController();
    setLoading(true);
    setError(null);

    const currentReq = fetchCharactersAsOf(bookId, activeChapterId, controller.signal);
    const prevReq = prevChapterId
      ? fetchCharactersAsOf(bookId, prevChapterId, controller.signal).catch(() => null)
      : Promise.resolve(null);

    Promise.all([currentReq, prevReq])
      .then(([currentList, prevList]) => {
        setCharacters(currentList);
        setPrevCharacters(prevList);
      })
      .catch((err: unknown) => {
        if (err instanceof DOMException && err.name === 'AbortError') return;
        setError(err instanceof Error ? err.message : 'Unknown error');
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setLoading(false);
        }
      });

    return () => controller.abort();
  }, [bookId, activeChapterId, prevChapterId]);

  const deltaMap: Record<string, CharacterDelta> = useMemo(
    () => computeChapterDelta(characters, prevCharacters, activeChapterId),
    [characters, prevCharacters, activeChapterId],
  );

  return { characters, deltaMap, loading, error };
}
