import { lazy, Suspense, useEffect, useState } from 'react';
import {
  CalendarDays,
  ChefHat,
  ChevronRight,
  CircleUserRound,
  LayoutDashboard,
  Leaf,
  ListChecks,
  Menu,
  Package,
  Settings2,
  ShoppingBasket,
  Store,
  Users,
  WifiOff,
  X,
} from 'lucide-react';
import { Link, NavLink, Route, Routes, useLocation } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { useApp, useOnline } from './state';
import { Catalog, RecipeDetail, Products } from './pages/Recipes';
import RecipeEditor from './pages/RecipeEditor';
import Planner from './pages/Planner';
import { ProfilePage, StoresPage, AuthPage, Onboarding } from './pages/Settings';
import { Empty, Loading } from './components/ui';
import { RequireSession, RequireCatalogueEditor, RequireKitchenSetup, useSession } from './session';
import { Welcome } from './pages/Account';
const KitchenPage = lazy(() => import('./pages/Kitchen'));
const PlanningPreferencesPage = lazy(() => import('./pages/PlanningPreferences'));
const HouseholdPage = lazy(() => import('./pages/Household'));
const CookingPage = lazy(() => import('./pages/Cooking'));
const PantryPage = lazy(() => import('./pages/Pantry'));
import NutritionTargetsPage from './pages/NutritionTargets';
const ShoppingSessionsPage = lazy(() => import('./pages/ShoppingSessions'));
const PeriodMenuPage = lazy(() => import('./pages/PeriodMenu'));
const PlanningMetadataPage = lazy(() => import('./pages/PlanningMetadata'));
const MenuPreviewPage = lazy(() => import('./pages/MenuPreview'));
const Shopping = lazy(() => import('./pages/Shopping'));
const mainNavigation = [
  { to: '/', label: 'Сегодня', icon: LayoutDashboard },
  { to: '/week', label: 'Меню недели', icon: CalendarDays },
  { to: '/recipes', label: 'Рецепты', icon: ChefHat },
  { to: '/shopping', label: 'Покупки', icon: ShoppingBasket },
];
const secondaryNavigation = [
  { to: '/stores', label: 'Магазины и бюджет', icon: Store },
  { to: '/products', label: 'Продукты', icon: Package },
  { to: '/household', label: 'Семья и порции', icon: Users },
  { to: '/pantry', label: 'Домашние запасы', icon: Package },
  { to: '/cooking', label: 'Готовка и остатки', icon: ChefHat },
  { to: '/profile', label: 'Мой профиль', icon: CircleUserRound },
];
export default function App() {
  const { mode, setMode, notify, toast } = useApp();
  const online = useOnline();
  const session = useSession();
  const location = useLocation();
  const query = useQueryClient();
  const [menu, setMenu] = useState(false);
  useEffect(() => {
    setMenu(false);
    document.querySelector('main')?.scrollTo(0, 0);
  }, [location.pathname]);
  const title = location.pathname.startsWith('/shopping-lists')
    ? 'Покупки'
    : [...mainNavigation, ...secondaryNavigation].find((item) => item.to === location.pathname)
        ?.label ||
      {
        '/cooking': 'Готовка и остатки',
        '/nutrition-settings': 'Белок и клетчатка',
        '/menu-preview': 'Подбор меню',
        '/menu-generation': 'Подбор на неделю',
        '/login': 'Вход',
        '/register': 'Регистрация',
        '/onboarding': 'Опрос',
        '/welcome': 'Добро пожаловать',
        '/setup/kitchen': 'Моя кухня',
        '/profile/kitchen': 'Моя кухня',
      }[location.pathname] ||
      'Рецепты';
  function changeMode(next: 'api' | 'demo') {
    query.cancelQueries();
    query.clear();
    setMode(next);
    notify(
      next === 'api'
        ? 'Используются только данные API'
        : 'Деморежим: учебные данные, без синхронизации с сервером',
    );
  }
  return (
    <>
      <a className="skip-link" href="#main">
        К содержимому
      </a>
      {menu && (
        <button
          className="sidebar-backdrop"
          aria-label="Закрыть навигацию"
          onClick={() => setMenu(false)}
        />
      )}
      <aside className={`sidebar ${menu ? 'open' : ''}`}>
        <Link className="brand" to="/">
          <span className="brand-mark">
            <Leaf size={22} />
          </span>
          smartmeal<span>.</span>
        </Link>
        <button
          className="mobile-close icon-button"
          onClick={() => setMenu(false)}
          aria-label="Закрыть меню"
        >
          <X size={20} />
        </button>
        <p className="nav-label">ВАШ ЛИЧНЫЙ ПЛАН</p>
        <nav aria-label="Основная навигация">
          {mainNavigation.map((item) => (
            <NavLink
              end={item.to === '/'}
              key={item.to}
              to={item.to === '/shopping' && mode === 'api' ? '/shopping-lists' : item.to}
              className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
            >
              <item.icon size={19} />
              {item.label}
              {item.to === '/shopping' && <span className="nav-badge">↗</span>}
            </NavLink>
          ))}
        </nav>
        <div className="nav-divider" />
        <nav aria-label="Настройки и справочники">
          {secondaryNavigation.map((item) => (
            <NavLink
              key={item.to}
              to={item.to === '/shopping' && mode === 'api' ? '/shopping-lists' : item.to}
              className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
            >
              <item.icon size={18} />
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-grow" />
        <div className="sidebar-tip">
          <Leaf size={26} />
          <h3>
            Маленький план.
            <br />
            Большая забота о себе.
          </h3>
          <p>Соберите меню под свой день — покупки посчитаем вместе.</p>
          <Link to="/onboarding">
            Настроить предпочтения <ChevronRight size={14} />
          </Link>
        </div>
        <label className="mode-select">
          <Settings2 size={16} />
          <span className="sr-only">Источник данных</span>
          <select
            aria-label="Источник данных"
            value={mode}
            onChange={(event) => changeMode(event.target.value as 'api' | 'demo')}
          >
            <option value="demo">Демонстрация</option>
            <option value="api">SmartMeal API</option>
          </select>
        </label>
        <Link
          className="sidebar-account"
          to={mode === 'demo' || session.profile ? '/profile' : '/login'}
        >
          <span className="avatar">{mode === 'demo' ? 'Д' : '?'}</span>
          <span>
            <b>
              {mode === 'demo'
                ? 'Демо-профиль'
                : session.profile
                  ? 'Личный профиль'
                  : 'Войти в аккаунт'}
            </b>
            <small>
              {mode === 'demo' ? 'Только в этом браузере' : session.profile ? 'Настройки' : 'Вход'}
            </small>
          </span>
          <ChevronRight size={15} />
        </Link>
      </aside>
      <div className="app-shell">
        <header className="topbar">
          <div className="breadcrumb">
            <button
              className="icon-button menu-toggle"
              aria-label="Открыть меню"
              onClick={() => setMenu(true)}
            >
              <Menu size={21} />
            </button>
            <span className="desktop-only">Моё пространство</span>
            <ChevronRight className="desktop-only" size={13} />
            <b>{title}</b>
          </div>
          <div className="topbar-right">
            <span className={`mode-badge ${mode === 'demo' ? 'demo' : 'live'}`}>
              <span />
              {mode === 'demo' ? 'Деморежим' : 'Мой план'}
            </span>
            <Link className="icon-button" aria-label="Профиль" to="/profile">
              <CircleUserRound size={22} />
            </Link>
          </div>
        </header>
        {mode === 'demo' && (
          <div className="mode-banner" role="status">
            <span>ДЕМО</span>Пример меню и цен. Для своего меню войдите в аккаунт.
          </div>
        )}
        {!online && (
          <div className="offline-banner" role="alert">
            <WifiOff size={17} />
            Нет сети. Запись и изменение покупок приостановлены; цены не считаются свежими.
          </div>
        )}
        {mode === 'api' && session.profile && session.error && (
          <div className="notice warning" role="status">
            Не удалось обновить профиль с сервера. Сетевая ошибка не завершает сессию.{' '}
            <button className="text-button" onClick={session.retry}>
              Повторить
            </button>
          </div>
        )}
        <main id="main">
          <Routes>
            <Route path="/login" element={<AuthPage />} />
            <Route path="/register" element={<AuthPage register />} />
            <Route element={<RequireSession />}>
              <Route
                path="/household"
                element={
                  <Suspense fallback={<Loading />}>
                    <HouseholdPage />
                  </Suspense>
                }
              />
              <Route
                path="/setup/kitchen"
                element={
                  <Suspense fallback={<Loading />}>
                    <KitchenPage initial />
                  </Suspense>
                }
              />
              <Route
                path="/profile/kitchen"
                element={
                  <Suspense fallback={<Loading />}>
                    <KitchenPage />
                  </Suspense>
                }
              />
              <Route
                path="/welcome"
                element={
                  <RequireKitchenSetup>
                    <Welcome />
                  </RequireKitchenSetup>
                }
              />
              <Route path="/" element={<Planner />} />
              <Route path="/week" element={<Planner week />} />
              <Route
                path="/menu-preview"
                element={
                  <Suspense fallback={<Loading />}>
                    <MenuPreviewPage />
                  </Suspense>
                }
              />
              <Route
                path="/planning-preferences"
                element={
                  <Suspense fallback={<Loading />}>
                    <PlanningPreferencesPage />
                  </Suspense>
                }
              />
              <Route
                path="/nutrition-settings"
                element={
                  <Suspense fallback={<Loading />}>
                    <NutritionTargetsPage />
                  </Suspense>
                }
              />
              <Route
                path="/menu-generation"
                element={
                  <Suspense fallback={<Loading />}>
                    <RequireKitchenSetup>
                      <PeriodMenuPage />
                    </RequireKitchenSetup>
                  </Suspense>
                }
              />
              <Route
                path="/recipes/:id/planning"
                element={
                  <Suspense fallback={<Loading />}>
                    <RequireCatalogueEditor>
                      <PlanningMetadataPage />
                    </RequireCatalogueEditor>
                  </Suspense>
                }
              />
              <Route
                path="/products/:id/planning"
                element={
                  <Suspense fallback={<Loading />}>
                    <RequireCatalogueEditor>
                      <PlanningMetadataPage product />
                    </RequireCatalogueEditor>
                  </Suspense>
                }
              />
              <Route path="/recipes" element={<Catalog />} />
              <Route
                path="/recipes/new"
                element={
                  <RequireCatalogueEditor>
                    <RecipeEditor />
                  </RequireCatalogueEditor>
                }
              />
              <Route
                path="/recipes/:id/edit"
                element={
                  <RequireCatalogueEditor>
                    <RecipeEditor />
                  </RequireCatalogueEditor>
                }
              />
              <Route path="/recipes/:id" element={<RecipeDetail />} />
              <Route path="/products" element={<Products />} />
              <Route
                path="/cooking"
                element={
                  <Suspense fallback={<Loading />}>
                    <CookingPage />
                  </Suspense>
                }
              />
              <Route
                path="/pantry"
                element={
                  <Suspense fallback={<Loading />}>
                    <PantryPage />
                  </Suspense>
                }
              />
              <Route
                path="/shopping-lists/:id?"
                element={
                  <Suspense fallback={<Loading />}>
                    <ShoppingSessionsPage />
                  </Suspense>
                }
              />
              <Route
                path="/shopping"
                element={
                  <Suspense fallback={<Loading />}>
                    <Shopping />
                  </Suspense>
                }
              />
              <Route path="/stores" element={<StoresPage />} />
              <Route path="/profile" element={<ProfilePage />} />
              <Route
                path="/onboarding"
                element={
                  <RequireKitchenSetup>
                    <Onboarding />
                  </RequireKitchenSetup>
                }
              />
              <Route
                path="*"
                element={
                  <Empty
                    title="Такой страницы нет"
                    action={
                      <Link to="/" className="button primary">
                        На главную
                      </Link>
                    }
                  >
                    Проверьте адрес или вернитесь к меню дня.
                  </Empty>
                }
              />
            </Route>
          </Routes>
          <footer>
            <span>
              smartmeal<span className="accent">.</span>
            </span>
            <span>
              Питание в вашем ритме <Leaf size={13} />
            </span>
          </footer>
        </main>
      </div>
      <nav className="bottom-nav" aria-label="Мобильная навигация">
        {[
          { to: '/', label: 'Сегодня', icon: LayoutDashboard },
          { to: '/recipes', label: 'Рецепты', icon: ChefHat },
          { to: '/shopping', label: 'Покупки', icon: ListChecks },
          { to: '/profile', label: 'Профиль', icon: CircleUserRound },
        ].map((item) => (
          <NavLink
            end={item.to === '/'}
            key={item.to}
            to={item.to === '/shopping' && mode === 'api' ? '/shopping-lists' : item.to}
          >
            <item.icon size={21} />
            <span>{item.label}</span>
          </NavLink>
        ))}
      </nav>
      {toast && (
        <div className="toast" role="status">
          {toast}
        </div>
      )}
    </>
  );
}
