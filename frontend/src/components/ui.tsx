import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import {
  AlertCircle,
  ArrowRight,
  Check,
  ChefHat,
  Clock3,
  RefreshCw,
  ServerOff,
  X,
} from 'lucide-react';
import { Link } from 'react-router';
import { ApiError } from '../api';
import { number } from '../domain';
import { recipeCover } from '../recipe-covers';
import type { Nutrition, RecipeSummary } from '../types';
export function Dialog({
  title,
  children,
  onClose,
  wide = false,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
  wide?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    const dialog = ref.current!;
    const previous = document.activeElement;
    dialog.showModal();
    const handler = (event: Event) => {
      event.preventDefault();
      closeRef.current();
    };
    dialog.addEventListener('cancel', handler);
    return () => {
      dialog.removeEventListener('cancel', handler);
      dialog.close();
      queueMicrotask(() => {
        if (previous instanceof HTMLElement && previous.isConnected)
          previous.focus({ preventScroll: true });
      });
    };
  }, []);
  return (
    <dialog
      ref={ref}
      className={wide ? 'wide' : ''}
      onClick={(event) => {
        if (event.target === ref.current) {
          const rect = ref.current.getBoundingClientRect();
          if (
            event.clientX < rect.left ||
            event.clientX > rect.right ||
            event.clientY < rect.top ||
            event.clientY > rect.bottom
          )
            onClose();
        }
      }}
      aria-label={title}
    >
      <div className="dialog-title">
        <h2>{title}</h2>
        <button className="icon-button" onClick={onClose} aria-label="Закрыть">
          <X size={20} />
        </button>
      </div>
      {children}
    </dialog>
  );
}
export function PageHeading({
  eyebrow,
  title,
  description,
  actions,
}: {
  eyebrow?: string;
  title: string;
  description?: string;
  actions?: ReactNode;
}) {
  return (
    <div className="page-heading">
      <div>
        {eyebrow && <p className="eyebrow">{eyebrow}</p>}
        <h1>{title}</h1>
        {description && <p className="muted">{description}</p>}
      </div>
      {actions && <div className="heading-actions">{actions}</div>}
    </div>
  );
}
export function Notice({
  children,
  tone = 'neutral',
}: {
  children: ReactNode;
  tone?: 'neutral' | 'warning' | 'success';
}) {
  return (
    <div className={`notice ${tone}`}>
      <AlertCircle size={18} />
      <div>{children}</div>
    </div>
  );
}
export function Empty({
  title,
  children,
  action,
}: {
  title: string;
  children: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="empty-state">
      <span className="empty-icon">
        <ChefHat size={30} />
      </span>
      <h2>{title}</h2>
      <p>{children}</p>
      {action}
    </div>
  );
}
export function Loading() {
  return (
    <div className="skeleton-grid" role="status" aria-label="Загрузка">
      <span className="sr-only">Загружаем данные…</span>
      {[1, 2, 3].map((i) => (
        <div className="skeleton" key={i} />
      ))}
    </div>
  );
}
export function ErrorState({ error, retry }: { error: Error; retry?: () => void }) {
  const unavailable = error instanceof ApiError && [404, 405, 501].includes(error.status);
  return (
    <div className="error-state" role="alert">
      <ServerOff size={30} />
      <h2>{unavailable ? 'Этот API ещё предстоит подключить' : 'Не удалось загрузить данные'}</h2>
      <p>
        {unavailable
          ? 'Серверный каталог рецептов пока недоступен. Переключитесь в деморежим, чтобы посмотреть интерфейс. Локальные рецепты не подставляются вместо ответа сервера.'
          : error.message}
      </p>
      {retry && (
        <button className="button" onClick={retry}>
          <RefreshCw size={16} /> Повторить
        </button>
      )}
    </div>
  );
}
export function BackendPending({ title, description }: { title: string; description: string }) {
  return (
    <Empty
      title={title}
      action={
        <Link className="button" to="/recipes">
          Перейти к рецептам <ArrowRight size={16} />
        </Link>
      }
    >
      {description} Демо этих сценариев доступно через переключатель режима в боковом меню.
    </Empty>
  );
}
export function RecipeImage({
  recipe,
  className = '',
}: {
  recipe: RecipeSummary;
  className?: string;
}) {
  const cover = recipeCover(recipe);
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [cover?.src]);
  return (
    <div className={`recipe-image ${className}`}>
      {cover && !failed ? (
        <>
          <img
            src={cover.src}
            srcSet={cover.srcSet}
            sizes="(max-width: 600px) 100vw, (max-width: 1100px) 50vw, 33vw"
            width={960}
            height={720}
            alt={cover.credit ? `Фото подачи: ${recipe.name}` : recipe.name}
            loading="lazy"
            decoding="async"
            onError={() => setFailed(true)}
          />
          {cover.credit && (
            <span
              className="generated-cover-label"
              title={`Фото: ${cover.credit.author}, ${cover.credit.license}. Кадр иллюстрирует категорию блюда; состав может отличаться.`}
            >
              Фото подачи
            </span>
          )}
        </>
      ) : (
        <ChefHat className="image-fallback" size={42} />
      )}
    </div>
  );
}

export function NutritionValues({
  nutrition,
  label,
}: {
  nutrition: Nutrition | null | undefined;
  label?: string;
}) {
  return (
    <div>
      <div className="nutrition-values">
        {(['calories', 'protein', 'fat', 'carbs'] as const).map((key, index) => (
          <div key={key}>
            <b>{nutrition ? number(nutrition[key], key === 'calories' ? 0 : 1) : '—'}</b>
            <span>{['ккал', 'белки, г', 'жиры, г', 'углеводы, г'][index]}</span>
          </div>
        ))}
      </div>
      {label && <p className="caption">{label}</p>}
    </div>
  );
}
export function Meta({ recipe }: { recipe: RecipeSummary }) {
  return (
    <div className="recipe-meta">
      <span>
        <Clock3 size={14} />
        {recipe.preparationTimeMin} мин
      </span>
      <span>{recipe.servings} порц.</span>
      {recipe.nutritionPerServing && (
        <span>{number(recipe.nutritionPerServing.calories)} ккал</span>
      )}
    </div>
  );
}
export function CheckChip({
  selected,
  onClick,
  children,
}: {
  selected: boolean;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      className={`check-chip ${selected ? 'selected' : ''}`}
      aria-pressed={selected}
      onClick={onClick}
    >
      {selected && <Check size={15} />} {children}
    </button>
  );
}
