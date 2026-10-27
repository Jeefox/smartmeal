import PortionMetadata from '../components/PortionMetadata';
import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../api';
import { Notice, PageHeading, Loading } from '../components/ui';
import { mealLabels } from '../mealplans';
import type { ApiMealType } from '../mealplans';
import { useApp, useOnline } from '../state';
import { BackendPending } from '../components/ui';

type RecipeSettings = {
  minPortions: number;
  maxPortions: number;
  portionStep: number;
  dishFamily: string | null;
  mealTypes?: ApiMealType[];
  equipmentIds?: number[];
};
type ProductSettings = {
  allergenDataComplete: boolean;
  verificationNote: string | null;
  allergenIds?: number[];
};
export default function PlanningMetadata(props: { product?: boolean }) {
  const { mode } = useApp();
  return mode === 'api' ? (
    <LivePlanningMetadata {...props} />
  ) : (
    <BackendPending
      title="Настройки подбора доступны в режиме API"
      description="Каталог сервера изменяется только в режиме SmartMeal API."
    />
  );
}
function LivePlanningMetadata({ product = false }: { product?: boolean }) {
  const { id } = useParams();
  const online = useOnline(),
    client = useQueryClient();
  const path = `/${product ? 'products' : 'recipes'}/${encodeURIComponent(id!)}/planning`;
  const query = useQuery({
    queryKey: ['planning-metadata', path],
    queryFn: ({ signal }) => request<RecipeSettings | ProductSettings>(path, { signal }),
  });
  const catalogues = useQuery({
    queryKey: ['planning-restrictions'],
    queryFn: ({ signal }) =>
      request<{
        allergens: { id: number; name: string }[];
        equipment: { id: number; name: string }[];
      }>('/planning-restrictions', { signal }),
  });
  const [draft, setDraft] = useState<RecipeSettings | ProductSettings | null>(null),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState('');
  useEffect(() => {
    if (query.data) setDraft(query.data);
  }, [query.data]);
  async function save(event: React.FormEvent) {
    event.preventDefault();
    if (busy || !online) return;
    setBusy(true);
    setMessage('');
    try {
      await request(path, { method: 'PUT', body: JSON.stringify(draft) });
      setMessage('Настройки сохранены. Существующие предпросмотры необходимо пересобрать.');
      client.invalidateQueries({ queryKey: ['planning-metadata', path] });
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Не удалось сохранить');
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <PageHeading
        title={product ? 'Проверка сведений об аллергенах' : 'Практичность порций'}
        description={
          product
            ? 'Отметка относится к полноте проверенных данных, а не гарантирует отсутствие аллергенов.'
            : 'Настройки используются автоматическим подбором. Для штучных ингредиентов оставьте целые порции.'
        }
      />
      {query.isPending && <Loading />}
      {query.error && <Notice>{query.error.message}</Notice>}
      {message && <Notice>{message}</Notice>}
      {draft && (
        <form className="panel period-options" onSubmit={save}>
          {product ? (
            <>
              <label className="period-accept">
                <input
                  type="checkbox"
                  checked={(draft as ProductSettings).allergenDataComplete}
                  onChange={(e) =>
                    setDraft({
                      ...draft,
                      allergenDataComplete: e.target.checked,
                    } as ProductSettings)
                  }
                />
                Сведения об аллергенах проверены и полны
              </label>
              <label>
                Источник и основание проверки
                <textarea
                  required={(draft as ProductSettings).allergenDataComplete}
                  maxLength={500}
                  value={(draft as ProductSettings).verificationNote || ''}
                  onChange={(e) =>
                    setDraft({
                      ...draft,
                      verificationNote: e.target.value,
                    } as ProductSettings)
                  }
                />
              </label>
            </>
          ) : (
            <div className="period-fields">
              {(['minPortions', 'maxPortions', 'portionStep'] as const).map((field, i) => (
                <label key={field}>
                  {['Минимум порций', 'Максимум порций', 'Шаг порций'][i]}
                  <input
                    type="number"
                    min="0.25"
                    max={field === 'portionStep' ? 2 : 4}
                    step="0.25"
                    required
                    value={(draft as RecipeSettings)[field]}
                    onChange={(e) => setDraft({ ...draft, [field]: Number(e.target.value) })}
                  />
                </label>
              ))}
              <label>
                Группа похожих блюд
                <input
                  maxLength={100}
                  placeholder="Например, oatmeal"
                  value={(draft as RecipeSettings).dishFamily || ''}
                  onChange={(e) => setDraft({ ...draft, dishFamily: e.target.value })}
                />
              </label>
            </div>
          )}
          {!product && (
            <fieldset>
              <legend>Приёмы пищи</legend>
              <div className="period-checks">
                {(['BREAKFAST', 'LUNCH', 'DINNER', 'SNACK'] as ApiMealType[]).map((meal) => (
                  <label key={meal}>
                    <input
                      type="checkbox"
                      checked={(draft as RecipeSettings).mealTypes?.includes(meal) || false}
                      onChange={(e) =>
                        setDraft({
                          ...draft,
                          mealTypes: e.target.checked
                            ? [...((draft as RecipeSettings).mealTypes || []), meal]
                            : ((draft as RecipeSettings).mealTypes || []).filter((x) => x !== meal),
                        } as RecipeSettings)
                      }
                    />
                    {mealLabels[meal]}
                  </label>
                ))}
              </div>
            </fieldset>
          )}
          <fieldset>
            <legend>{product ? 'Аллергены продукта' : 'Необходимое оборудование'}</legend>
            <div className="period-checks">
              {(product ? catalogues.data?.allergens : catalogues.data?.equipment)?.map(
                (choice) => (
                  <label key={choice.id}>
                    <input
                      type="checkbox"
                      checked={
                        (product
                          ? (draft as ProductSettings).allergenIds
                          : (draft as RecipeSettings).equipmentIds
                        )?.includes(choice.id) || false
                      }
                      onChange={(e) => {
                        const field = product ? 'allergenIds' : 'equipmentIds';
                        const values =
                          (product
                            ? (draft as ProductSettings).allergenIds
                            : (draft as RecipeSettings).equipmentIds) || [];
                        setDraft({
                          ...draft,
                          [field]: e.target.checked
                            ? [...values, choice.id]
                            : values.filter((id) => id !== choice.id),
                        });
                      }}
                    />
                    {choice.name}
                  </label>
                ),
              )}
            </div>
          </fieldset>
          <button className="button primary" disabled={busy || !online}>
            Сохранить
          </button>
        </form>
      )}
      <PortionMetadata id={id!} product={product} />
      <Link to={product ? '/products' : `/recipes/${id}`}>Вернуться в каталог</Link>
    </>
  );
}
