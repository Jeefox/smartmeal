import type { RecipeSnapshot } from '../mealplans';
import type { PersonPortion } from '../household';
import { number } from '../domain';
import { roleLabels } from '../balanced-meals';
export function FiberLabel({ known, complete }: { known?: number; complete?: boolean }) {
  if (!complete && !known) return <span>Клетчатка: нет данных</span>;
  return (
    <span>
      Клетчатка: {number(known ?? 0, 1)} г{complete ? '' : ' — известная часть, данные неполные'}
    </span>
  );
}
export default function MealComposition({
  recipe,
  people,
  onReplace,
  onLock,
  busy,
}: {
  recipe: RecipeSnapshot;
  people: PersonPortion[];
  onReplace?: (id: string) => void;
  onLock?: (id: string, locked: boolean) => void;
  busy?: boolean;
}) {
  if (!recipe.components?.length) return null;
  return (
    <section className="meal-composition" aria-label="Состав приёма пищи">
      <h3>Количество для каждого</h3>
      {recipe.components.map((c) => (
        <article className="panel" key={c.id}>
          <h4>{c.recipe.name}</h4>
          <p className="caption">{roleLabels[c.role]}</p>
          <ul>
            {c.amounts.map((a) => (
              <li key={a.participantId}>
                <strong>
                  {people.find((p) => p.participantId === a.participantId)?.name || 'Участник'}
                </strong>
                : {c.profile.yieldQuality === 'ESTIMATED' ? '≈ ' : ''}
                {number(a.cookedG)} г готового
              </li>
            ))}
          </ul>
          {c.profile.yieldQuality === 'ESTIMATED' && (
            <p className="caption">Выход приблизительный. Уточните вес после приготовления.</p>
          )}
          <details>
            <summary>Источник выхода и рецепт</summary>
            <p>{c.profile.source}</p>
            <p>{c.recipe.instructions}</p>
            <p className="caption">Для приготовления на всех, сырые съедобные ингредиенты:</p>
            <ul>
              {c.recipe.ingredients.map((i, index) => (
                <li key={`${i.productId}-${index}`}>
                  {i.productName}:{' '}
                  {number(i.quantityG * c.amounts.reduce((total, a) => total + a.multiplier, 0), 2)}{' '}
                  г
                </li>
              ))}
            </ul>
          </details>
          {onReplace && (
            <button className="button" disabled={busy || c.locked} onClick={() => onReplace(c.id)}>
              Заменить{' '}
              {c.role === 'SIDE'
                ? 'гарнир'
                : c.role === 'VEGETABLE'
                  ? 'овощную часть'
                  : 'компонент'}
            </button>
          )}
          {onLock && (
            <button
              className="text-button"
              disabled={busy}
              aria-pressed={c.locked}
              onClick={() => onLock(c.id, !c.locked)}
            >
              {c.locked ? 'Открепить компонент' : 'Оставить компонент'}
            </button>
          )}
        </article>
      ))}
      <ul>
        {people.map((p) => (
          <li key={p.participantId}>
            <strong>{p.name}</strong>: {number(p.nutrition.calories)} ккал · белок{' '}
            {number(p.nutrition.protein, 1)} г ·{' '}
            <FiberLabel known={p.nutrition.knownFiber} complete={p.nutrition.fiberComplete} />
          </li>
        ))}
      </ul>
    </section>
  );
}
