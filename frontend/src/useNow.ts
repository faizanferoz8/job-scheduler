import { useEffect, useState } from 'react';

/** The current time, refreshed often enough for countdowns and lease bars to move. */
export function useNow(intervalMillis = 250): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMillis);
    return () => clearInterval(timer);
  }, [intervalMillis]);
  return now;
}
