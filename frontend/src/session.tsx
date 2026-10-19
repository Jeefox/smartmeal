import { createContext, useContext, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, Navigate, Outlet, useLocation } from 'react-router';
import { api, ApiError } from './api';
import { useApp } from './state';
import { ErrorState, Loading } from './components/ui';
import type { UserProfile } from './types';
export const SESSION_KEY = ['session', 'profile'] as const;
export function safeNext(value: string | null) {
  return value &&
    value.startsWith('/') &&
    !value.startsWith('//') &&
    !/[\\\u0000-\u0020]/.test(value) &&
    !/^\/(login|register)(?:[/?#]|$)/.test(value)
    ? value
    : '/recipes';
}
function useSessionState() {
  const { mode, notify } = useApp();
  const client = useQueryClient();
  const [ending, setEnding] = useState(false);
  const exiting = useRef(false);
  const query = useQuery({
    queryKey: SESSION_KEY,
    queryFn: async ({ signal }) => {
      try {
        return await api.profile(signal);
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) return null;
        throw error;
      }
    },
    enabled: mode === 'api',
    staleTime: 0,
    gcTime: 0,
    retry: false,
    refetchOnWindowFocus: true,
    refetchOnReconnect: true,
  });
  const current = useRef(query.data);
  current.current = query.data;
  useEffect(() => {
    const handler = () => {
      if (mode !== 'api') return;
      const signedIn = Boolean(current.current);
      client.cancelQueries({ predicate: (q) => q.queryKey[0] !== 'session' });
      client.removeQueries({ predicate: (q) => q.queryKey[0] !== 'session' });
      client.setQueryData(SESSION_KEY, null);
      if (signedIn && !exiting.current) notify('Сессия завершилась. Войдите снова.');
    };
    window.addEventListener('smartmeal:unauthorized', handler);
    return () => window.removeEventListener('smartmeal:unauthorized', handler);
  }, [mode, client, notify]);
  async function refresh() {
    await client.cancelQueries();
    client.removeQueries({ predicate: (q) => q.queryKey[0] !== 'session' });
    const profile = await api.profile();
    client.setQueryData(SESSION_KEY, profile);
    return profile;
  }
  async function logout() {
    if (exiting.current) return;
    exiting.current = true;
    setEnding(true);
    try {
      await api.logout();
      await client.cancelQueries();
      client.clear();
      client.setQueryData(SESSION_KEY, null);
      if ('caches' in window) {
        for (const key of await caches.keys())
          if (key.startsWith('smartmeal-user-')) await caches.delete(key);
      }
      notify('Вы вышли из аккаунта');
    } finally {
      exiting.current = false;
      setEnding(false);
    }
  }
  return {
    logout,
    ending,
    profile: query.data,
    loading: query.isPending,
    error: query.error,
    retry: () => query.refetch(),
    refresh,
    saveProfile: (value: UserProfile) => {
      client.setQueryData(SESSION_KEY, value);
      client.invalidateQueries({ queryKey: ['meal-plan'] });
    },
  };
}
type Session = ReturnType<typeof useSessionState>;
const Context = createContext<Session | null>(null);
export function SessionProvider({ children }: { children: ReactNode }) {
  const session = useSessionState();
  return <Context.Provider value={session}>{children}</Context.Provider>;
}
export function useSession() {
  const value = useContext(Context);
  if (!value) throw new Error('SessionProvider is missing');
  return value;
}
export function RequireSession() {
  const { mode } = useApp();
  const session = useSession();
  const location = useLocation();
  if (mode === 'demo' || session.profile) return <Outlet />;
  if (session.loading) return <Loading />;
  if (session.error)
    return (
      <>
        <ErrorState error={session.error} retry={session.retry} />
        <Link to="/login" className="button">
          Перейти ко входу
        </Link>
      </>
    );
  return (
    <Navigate
      replace
      to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`}
    />
  );
}
