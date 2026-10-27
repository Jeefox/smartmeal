import { createContext, useContext, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, Navigate, Outlet, useLocation } from 'react-router';
import { api, ApiError } from './api';
import { advanceBoundary, clearPrivateStorage } from './session-boundary';
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
  const channel = useRef<BroadcastChannel | null>(null);
  const owner = useRef<string | null | undefined>(undefined);
  const { mode, notify } = useApp();
  const client = useQueryClient();
  const [ending, setEnding] = useState(false);
  const exiting = useRef(false);
  const query = useQuery({
    queryKey: SESSION_KEY,
    queryFn: async ({ signal }) => {
      try {
        const profile = await api.profile(signal);
        if (owner.current && owner.current !== profile.userId) clearPrivateState();
        owner.current = profile.userId;
        return profile;
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
  function clearPrivateState() {
    advanceBoundary();
    clearPrivateStorage();
    void client.cancelQueries({ predicate: (q) => q.queryKey[0] !== 'session' });
    client.removeQueries({ predicate: (q) => q.queryKey[0] !== 'session' });
  }
  useEffect(() => {
    const next = query.data?.userId ?? null;
    if (owner.current && owner.current !== next) clearPrivateState();
    owner.current = next;
  }, [query.data?.userId, client]);
  useEffect(() => {
    if (mode !== 'api' || typeof BroadcastChannel === 'undefined') return;
    const bus = new BroadcastChannel('smartmeal-session');
    channel.current = bus;
    bus.onmessage = (event) => {
      if (event.data !== 'changed') return;
      clearPrivateState();
      owner.current = null;
      current.current = null;
      void client.resetQueries({ queryKey: SESSION_KEY });
    };
    return () => {
      channel.current = null;
      bus.close();
    };
  }, [mode, client]);
  const current = useRef(query.data);
  current.current = query.data;
  useEffect(() => {
    const handler = () => {
      if (mode !== 'api') return;
      const signedIn = Boolean(current.current);
      void client.cancelQueries({ queryKey: SESSION_KEY });
      if (signedIn) clearPrivateState();
      owner.current = null;
      current.current = null;
      if (signedIn && !exiting.current) channel.current?.postMessage('changed');
      client.setQueryData(SESSION_KEY, null);
      if (signedIn && !exiting.current) notify('Сессия завершилась. Войдите снова.');
    };
    window.addEventListener('smartmeal:unauthorized', handler);
    return () => window.removeEventListener('smartmeal:unauthorized', handler);
  }, [mode, client, notify]);
  async function refresh() {
    clearPrivateState();
    await client.cancelQueries();
    const profile = await api.profile();
    owner.current = profile.userId;
    client.setQueryData(SESSION_KEY, profile);
    channel.current?.postMessage('changed');
    return profile;
  }
  async function logout() {
    if (exiting.current) return;
    exiting.current = true;
    setEnding(true);
    try {
      await api.logout();
      clearPrivateState();
      owner.current = null;
      channel.current?.postMessage('changed');
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
      if (value.userId !== current.current?.userId) return;
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
  if (mode === 'demo' || session.profile)
    return <Outlet key={mode === 'demo' ? 'demo' : session.profile!.userId} />;
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

export function RequireCatalogueEditor({ children }: { children: ReactNode }) {
  const { mode } = useApp();
  const { profile } = useSession();
  return mode === 'demo' || profile?.canEditCatalogue ? children : <NoticeForEditor />;
}
function NoticeForEditor() {
  return (
    <div className="panel">
      <h2>Редактирование каталога</h2>
      <p>Эта функция доступна редактору каталога.</p>
      <Link to="/recipes" className="button">
        К рецептам
      </Link>
    </div>
  );
}

export function RequireKitchenSetup({ children }: { children: ReactNode }) {
  const { mode } = useApp();
  const { profile } = useSession();
  const location = useLocation();
  return mode === 'api' && profile?.equipmentConfigured === false ? (
    <Navigate
      replace
      to={`/setup/kitchen?next=${encodeURIComponent(location.pathname + location.search)}`}
    />
  ) : (
    children
  );
}
