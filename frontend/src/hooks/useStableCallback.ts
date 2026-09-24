import { useCallback, useLayoutEffect, useRef } from 'react';

/**
 * Returns a function with a permanently stable identity that always invokes the
 * latest `callback`. Lets consumers pass event handlers to memoized children
 * (e.g. React.memo'd components) without the handler's own dependencies
 * (filters, sort state, etc.) forcing a new prop reference on every render.
 */
export function useStableCallback<A extends unknown[], R>(callback: (...args: A) => R): (...args: A) => R {
  const callbackRef = useRef(callback);

  useLayoutEffect(() => {
    callbackRef.current = callback;
  });

  return useCallback((...args: A) => callbackRef.current(...args), []);
}
