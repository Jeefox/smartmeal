import { createContext, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import type { MealType, Mode, PlanEntry, Profile, Recipe, ShoppingList } from './types';
import { defaultProfile, demoRecipes, makeDemoRecipe } from './demo';
import { dateKey } from './domain';
function read<T>(key: string, fallback: T): T {
  try {
    return JSON.parse(localStorage.getItem(`smartmeal.v2.${key}`) || 'null') ?? fallback;
  } catch {
    return fallback;
  }
}
function useStored<T>(key: string, initial: T) {
  const [value, setValue] = useState<T>(() => read(key, initial));
  useEffect(() => {
    try {
      localStorage.setItem(`smartmeal.v2.${key}`, JSON.stringify(value));
    } catch {
      /* The UI remains usable in memory; persistence is explicitly demo-only. */
    }
  }, [key, value]);
  return [value, setValue] as const;
}
export function useOnline() {
  const [online, set] = useState(navigator.onLine);
  useEffect(() => {
    const on = () => set(true),
      off = () => set(false);
    window.addEventListener('online', on);
    window.addEventListener('offline', off);
    return () => {
      window.removeEventListener('online', on);
      window.removeEventListener('offline', off);
    };
  }, []);
  return online;
}
const initialDate = dateKey(new Date());
const initialEntries: PlanEntry[] = [0, 1, 2].map((index) => ({
  id: `sample-${index}`,
  date: initialDate,
  meal: (['Завтрак', 'Обед', 'Ужин'] as MealType[])[index],
  recipe: demoRecipes[index],
  portions: index === 1 ? 1.5 : 1,
  locked: false,
}));
function useAppState() {
  const [mode, setMode] = useStored<Mode>('mode', 'demo');
  const [profile, setProfile] = useStored<Profile>('demo.profile', defaultProfile);
  const [recipes, setRecipes] = useStored<Recipe[]>('demo.recipes', demoRecipes);
  const [entries, setEntries] = useStored<PlanEntry[]>('demo.entries', initialEntries);
  const [favorites, setFavorites] = useStored<string[]>('demo.favorites', []);
  const [shopping, setShopping] = useStored<ShoppingList | null>('demo.shopping', null);
  const [offlineConsent, setOfflineConsent] = useStored('demo.offlineConsent', false);
  const [selectedDate, setSelectedDate] = useState(initialDate);
  const [toast, setToast] = useState('');
  useEffect(() => {
    if (!toast) return;
    const timeout = setTimeout(() => setToast(''), 4500);
    return () => clearTimeout(timeout);
  }, [toast]);
  function changeEntries(next: PlanEntry[]) {
    setEntries(next);
    setShopping((current) => (current ? { ...current, stale: true } : null));
  }
  function addEntry(recipe: Recipe, meal: MealType, date: string, portions = 1) {
    changeEntries([
      ...entries,
      {
        id: crypto.randomUUID(),
        date,
        meal,
        recipe: structuredClone(recipe),
        portions,
        locked: false,
      },
    ]);
    setToast('Добавлено в демоменю');
  }
  function saveRecipe(input: Parameters<typeof makeDemoRecipe>[0]) {
    const recipe = makeDemoRecipe(input);
    setRecipes((current) => [recipe, ...current.filter((item) => item.id !== recipe.id)]);
    return recipe;
  }
  return {
    mode,
    setMode,
    profile,
    setProfile,
    recipes,
    saveRecipe,
    entries,
    changeEntries,
    addEntry,
    favorites,
    toggleFavorite: (id: string) =>
      setFavorites((current) =>
        current.includes(id) ? current.filter((x) => x !== id) : [...current, id],
      ),
    shopping,
    setShopping,
    offlineConsent,
    setOfflineConsent,
    selectedDate,
    setSelectedDate,
    toast,
    notify: setToast,
  };
}
type AppState = ReturnType<typeof useAppState>;
const Context = createContext<AppState | null>(null);
export function AppProvider({ children }: { children: ReactNode }) {
  const state = useAppState();
  return <Context.Provider value={state}>{children}</Context.Provider>;
}
export function useApp() {
  const value = useContext(Context);
  if (!value) throw new Error('AppProvider is missing');
  return value;
}
