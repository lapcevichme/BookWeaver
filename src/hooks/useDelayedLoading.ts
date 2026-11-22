import { useEffect, useState } from 'react';

/**
 * Delays showing a skeleton/shimmer so fast local responses (< delayMs) never flash.
 */
export function useDelayedLoading(loading: boolean, delayMs = 180): boolean {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    if (!loading) {
      setVisible(false);
      return;
    }

    const timer = window.setTimeout(() => {
      setVisible(true);
    }, delayMs);

    return () => window.clearTimeout(timer);
  }, [loading, delayMs]);

  return visible;
}
