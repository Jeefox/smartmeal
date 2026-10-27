import ReplacementChoices from './ReplacementChoices';
import MealComposition from '../MealComposition';
import { Link } from 'react-router';
import { Dialog } from '../ui';
import { number, labelDate } from '../../domain';
import { mealLabels } from '../../mealplans';
import type { Alternative, Selection } from '../../period-planning';
export default function MealChoiceDialog({
  entry,
  alternatives,
  busy,
  editable,
  onClose,
  onAlternatives,
  onReplace,
  onLock,
  onReplaceComponent,
  onLockComponent,
}: {
  onReplaceComponent?: (id: string) => void;
  onLockComponent?: (id: string, locked: boolean) => void;
  entry: Selection;
  alternatives: Alternative[] | null;
  busy: boolean;
  editable: boolean;
  onClose: () => void;
  onAlternatives: () => void;
  onReplace: (a: Alternative) => void;
  onLock: () => void;
}) {
  return (
    <Dialog title={entry.recipe.name} onClose={onClose} wide>
      <p className="caption">
        {mealLabels[entry.mealType]} · {labelDate(entry.date)} · {entry.recipe.preparationTimeMin}{' '}
        мин
      </p>
      {!entry.recipe.components?.length && (
        <p>
          <strong>{number(entry.portions, 2)} порц. всего</strong> ·{' '}
          {number(entry.recipe.nutritionPerServing.calories * entry.portions)} ккал
        </p>
      )}
      <MealComposition
        recipe={entry.recipe}
        people={entry.personPortions || []}
        onReplace={editable ? onReplaceComponent : undefined}
        onLock={editable ? onLockComponent : undefined}
        busy={busy}
      />
      {!entry.recipe.components?.length && (entry.personPortions?.length || 0) > 1 && (
        <section aria-label="Порции по людям">
          <h3>Порция каждого</h3>
          <ul className="household-portions">
            {entry.personPortions!.map((p) => (
              <li key={p.participantId}>
                <strong>{p.name}</strong>: {number(p.portions, 2)} порц. ·{' '}
                {number(p.nutrition.calories)} ккал
              </li>
            ))}
          </ul>
        </section>
      )}
      <Link className="text-link" to={`/recipes/${entry.recipe.recipeId}`}>
        Открыть рецепт и приготовление
      </Link>
      <details className="meal-explanations">
        <summary>Почему это блюдо</summary>
        {entry.explanations.map((t) => (
          <p key={t}>{t}</p>
        ))}
      </details>
      {editable ? (
        <div className="period-actions">
          <button
            className="button primary"
            disabled={busy || entry.locked}
            onClick={onAlternatives}
          >
            Заменить блюдо
          </button>
          <button
            className="button secondary"
            disabled={busy}
            aria-pressed={entry.locked}
            onClick={onLock}
          >
            {entry.locked ? 'Открепить блюдо' : 'Оставить это блюдо'}
          </button>
        </div>
      ) : (
        <p className="caption">
          Этот вариант доступен для просмотра. Для изменения сохранённого или устаревшего меню нужен
          новый подбор.
        </p>
      )}
      {entry.locked && editable && (
        <p className="caption">Блюдо останется при пересборке. Для замены сначала открепите его.</p>
      )}
      {alternatives !== null && (
        <ReplacementChoices
          alternatives={alternatives}
          busy={busy}
          editable={editable}
          onConfirm={onReplace}
        />
      )}
      {busy && <p role="status">Обновляем меню…</p>}
    </Dialog>
  );
}
