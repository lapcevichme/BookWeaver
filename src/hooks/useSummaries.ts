import { useEffect, useState } from 'react';
import { fetchSummariesAsOf } from '../services/api';
import type { BookSummaries } from '../types/knowledge';

export function useSummaries(bookId: string | null, activeChapterId: string | null) {
  const [summaries, setSummaries] = useState<BookSummaries | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!bookId || !activeChapterId) {
      setSummaries(null);
      return;
    }

    const controller = new AbortController();
    setLoading(true);
    setError(null);

    fetchSummariesAsOf(bookId, activeChapterId, controller.signal)
      .then((data) => {
        setSummaries(data);
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
  }, [bookId, activeChapterId]);

  return { summaries, loading, error };
}
