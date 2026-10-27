import { usePantryCommand } from '../pantry';
import { Notice } from './ui';
import type { MealPlanEntry } from '../mealplans';
import { number } from '../domain';
import { readyComponents, useTodayCooking } from '../today-cooking';
export default function TodayPortions({
  entry,
  date,
  confirmed,
}: {
  entry: MealPlanEntry;
  date: string;
  confirmed: boolean;
}) {
  const command = usePantryCommand();
  const cooking = useTodayCooking(date, confirmed),
    links = readyComponents(cooking.data, entry.id);

  return (
    <div className="today-portions">
      {command.message && <Notice>{command.message}</Notice>}
      {!!entry.recipeSnapshot.components?.length &&
        entry.personPortions?.map((person) => (
          <p key={person.participantId}>
            <strong>{person.name}</strong>:{' '}
            {entry.recipeSnapshot
              .components!.map((c) => {
                const amount = c.amounts.find((a) => a.participantId === person.participantId);
                return `${c.recipe.name.toLowerCase()} ${amount ? `≈ ${number(amount.cookedG)} г` : '— количество не указано'}`;
              })
              .join(' · ')}
            <span className="caption"> · {number(person.nutrition.calories)} ккал</span>
          </p>
        ))}
      {confirmed &&
        cooking.data?.candidates
          .filter((c) => c.ref.entryId === entry.id)
          .flatMap((c) =>
            cooking
              .data!.batches.filter(
                (b) =>
                  b.status === 'COOKED' &&
                  !b.expired &&
                  b.freeG > 0 &&
                  b.useBy >= date &&
                  b.fingerprint === c.fingerprint,
              )
              .slice(0, 1)
              .map((b) => (
                <div key={c.ref.componentId}>
                  <p className="caption">
                    Есть готовое: {b.name.toLowerCase()} · свободно {number(b.freeG)} г
                  </p>
                  <button
                    className="button secondary"
                    disabled={command.busy || !command.online || cooking.isFetching}
                    onClick={() =>
                      command.run(`/me/cooking-batches/${b.id}/assign`, {
                        expectedRevision: cooking.data!.revision,
                        entries: [c.ref],
                      })
                    }
                  >
                    Использовать готовое
                  </button>
                </div>
              )),
          )}
      {links
        .filter((x) => x.allocation.status === 'ACTIVE')
        .map(({ batch, allocation }) => (
          <p
            className={`caption ${batch.status === 'COOKED' && !batch.expired ? 'today-ready' : ''}`}
            key={allocation.id}
          >
            {batch.expired
              ? `Проверьте ${batch.name.toLowerCase()}: указанная дата хранения истекла`
              : batch.status === 'COOKED'
                ? `Уже готово: ${batch.name.toLowerCase()} · ${number(allocation.cookedG)} г на всех`
                : `Общая готовка: ${batch.name.toLowerCase()} · ${number(allocation.cookedG)} г на всех`}
          </p>
        ))}
    </div>
  );
}
