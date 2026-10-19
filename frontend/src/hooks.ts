import { useEffect, useState } from 'react';
export function useDebounce<T>(value: T, delay = 300) {
  const [debounced, set] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => set(value), delay);
    return () => clearTimeout(id);
  }, [value, delay]);
  return debounced;
}
