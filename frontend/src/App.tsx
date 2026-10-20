import { useEffect, useState } from 'react';
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
  WifiOff,
  X,
} from 'lucide-react';
import { Link, NavLink, Route, Routes, useLocation } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { useApp, useOnline } from './state';
import { Catalog, RecipeDetail, Products } from './pages/Recipes';
import RecipeEditor from './pages/RecipeEditor';
import Planner from './pages/Planner';
import Shopping from './pages/Shopping';
import { ProfilePage, StoresPage, AuthPage, Onboarding } from './pages/Settings';
import { Empty } from './components/ui';
import { RequireSession, useSession } from './session';
import { Welcome } from './pages/Account';
const mainNavigation = [
  { to: '/', label: 'Сегодня', icon: LayoutDashboard },
  { to: '/week', label: 'Меню недели', icon: CalendarDays },
  { to: '/recipes', label: 'Рецепты', icon: ChefHat },
  { to: '/shopping', label: 'Покупки', icon: ShoppingBasket },
];
const secondaryNavigation = [
  { to: '/stores', label: 'Магазины и бюджет', icon: Store },
  { to: '/products', label: 'Продукты', icon: Package },
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
  const title =
    [...mainNavigation, ...secondaryNavigation].find((item) => item.to === location.pathname)
      ?.label ||
    {
      '/login': 'Вход',
      '/register': 'Регистрация',
      '/onboarding': 'Опрос',
      '/welcome': 'Добро пожаловать',
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
              to={item.to}
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
              to={item.to}
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
              {mode === 'demo'
                ? 'Только в этом браузере'
                : session.profile
                  ? 'Данные на сервере'
                  : 'Серверная сессия'}
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
              {mode === 'demo' ? 'Деморежим' : 'Данные API'}
            </span>
            <Link className="icon-button" aria-label="Профиль" to="/profile">
              <CircleUserRound size={22} />
            </Link>
          </div>
        </header>
        <div className={`mode-banner ${mode === 'demo' ? '' : 'api-banner'}`} role="status">
          {mode === 'demo' ? (
            <>
              <span>ДЕМО</span>Учебные меню и цены. Изменения сохраняются только в этом браузере.
            </>
          ) : (
            <>
              <span>API</span>Реальные продукты, рецепты и профиль. Остальные модули подключаются по
              мере готовности бэкенда.
            </>
          )}
        </div>
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
              <Route path="/welcome" element={<Welcome />} />
              <Route path="/" element={<Planner />} />
              <Route path="/week" element={<Planner week />} />
              <Route path="/recipes" element={<Catalog />} />
              <Route path="/recipes/new" element={<RecipeEditor />} />
              <Route path="/recipes/:id/edit" element={<RecipeEditor />} />
              <Route path="/recipes/:id" element={<RecipeDetail />} />
              <Route path="/products" element={<Products />} />
              <Route path="/shopping" element={<Shopping />} />
              <Route path="/stores" element={<StoresPage />} />
              <Route path="/profile" element={<ProfilePage />} />
              <Route path="/onboarding" element={<Onboarding />} />
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
          <NavLink end={item.to === '/'} key={item.to} to={item.to}>
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
