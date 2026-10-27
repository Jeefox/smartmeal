import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { kitchenApi, kitchenKey, kitchenChoices } from '../kitchen';
import type { Kitchen } from '../kitchen';
import { useSession } from '../session';
export function KitchenSummary() {
  const { profile } = useSession();
  const q = useQuery({
    queryKey: kitchenKey(profile!.userId),
    queryFn: ({ signal }) => kitchenApi.get(signal),
  });
  const value = q.data as Kitchen | undefined;
  const labels = value
    ? kitchenChoices(value.available)
        .filter((c) => c.ids.some((id) => value.equipmentIds.includes(id)))
        .map((c) => c.name)
    : [];
  return (
    <section className="panel kitchen-summary">
      <div>
        <h2>Моя кухня</h2>
        <p>
          {q.isPending
            ? 'Загружаем…'
            : q.error
              ? 'Выбор можно посмотреть в настройках'
              : labels.length
                ? labels.join(' · ')
                : value?.configured
                  ? 'Без техники'
                  : 'Техника ещё не выбрана'}
        </p>
      </div>
      <Link className="button" to="/profile/kitchen">
        Изменить
      </Link>
    </section>
  );
}
