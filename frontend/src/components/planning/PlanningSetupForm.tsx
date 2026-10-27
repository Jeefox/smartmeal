import { useState } from 'react';
import { Link } from 'react-router';
import { ChevronDown, SlidersHorizontal, Sparkles, Store, Users, CalendarDays } from 'lucide-react';
import { labelDate, number, shiftDate } from '../../domain';
import { Loading, Notice } from '../ui';
import type { PlanningDraft } from '../../planning-preferences';
import type { HouseholdMember } from '../../household';
import { mealLabels } from '../../mealplans';
const meals = ['BREAKFAST', 'LUNCH', 'DINNER'] as const;
type Props = {
  draft: PlanningDraft;
  onChange: (value: PlanningDraft) => void;
  members: HouseholdMember[];
  chains: { id: number; name: string }[];
  membersLoading: boolean;
  chainsLoading: boolean;
  membersError: boolean;
  chainsError: boolean;
  retryMembers: () => void;
  retryChains: () => void;
  busy: boolean;
  online: boolean;
  target: number | null | undefined;
  onSubmit: () => void;
  onCancel?: () => void;
  editing: boolean;
  advanced: boolean;
  onAdvanced: (value: boolean) => void;
  invalid: string | null;
};
export default function PlanningSetupForm(p: Props) {
  const d = p.draft,
    [custom, setCustom] = useState(![3, 5, 7].includes(d.days)),
    [storesOpen, setStoresOpen] = useState(!d.chains.length);
  const change = (patch: Partial<PlanningDraft>) => p.onChange({ ...d, ...patch });
  const missingMembers =
    !p.membersLoading && !p.membersError
      ? d.members.filter((id) => !p.members.some((m) => m.id === id))
      : [];
  const missingChains =
    !p.chainsLoading && !p.chainsError
      ? d.chains.filter((id) => !p.chains.some((c) => c.id === id))
      : [];
  return (
    <form
      className="planning-setup"
      onSubmit={(e) => {
        e.preventDefault();
        p.onSubmit();
      }}
      aria-busy={p.busy}
    >
      <div className="panel">
        <label className="check">
          <input
            type="checkbox"
            checked={d.balanced === true}
            disabled={p.busy}
            onChange={(e) => change({ balanced: e.target.checked })}
          />
          Подбирать нормальные количества и сочетания с гарниром
        </label>
        <p className="caption">
          Количество еды ограничивается отдельно для каждого человека. Цели питания и
          приблизительный выход показываются явно.
        </p>
        <Link className="text-link" to="/profile#nutrition">
          Настроить белок, клетчатку и размер еды
        </Link>
      </div>
      <section className="panel setup-section">
        <h2>Короткий список покупок</h2>
        <label>
          Максимум разных продуктов на весь период
          <select
            value={d.maxShoppingProducts ?? ''}
            disabled={p.busy}
            onChange={(e) =>
              change({ maxShoppingProducts: e.target.value ? Number(e.target.value) : null })
            }
          >
            {d.maxShoppingProducts == null && (
              <option value="">Без ограничения · старые настройки</option>
            )}
            {[
              ...new Set([
                7,
                8,
                9,
                10,
                ...(d.maxShoppingProducts != null ? [d.maxShoppingProducts] : []),
              ]),
            ]
              .sort((a, b) => a - b)
              .map((n) => (
                <option key={n} value={n}>
                  До {n} продуктов
                </option>
              ))}
          </select>
        </label>
        <p className="caption">
          Разные блюда используют общие продукты: например, гречка с курицей и гречка с молоком.
          Сырой вес суммируется, покупка считается целыми упаковками. Есть дома — не входит в лимит;
          масло и специи, если их нужно купить, тоже считаются.
        </p>
        <p className="caption">
          Повторы одинаковых блюд ограничиваются отдельно. Учитываем меню за предыдущие 21 день;
          знакомый гарнир можно использовать чаще, чем одно и то же блюдо.
        </p>
      </section>
      <fieldset disabled={p.busy} className="planning-fields-reset">
        <legend className="sr-only">Условия подбора меню</legend>
        <section className="panel setup-section">
          <div className="setup-section-title">
            <Users size={20} />
            <h2>Для кого готовим</h2>
          </div>
          <fieldset className="setup-choices">
            <legend className="sr-only">Для кого готовим</legend>
            <label className="setup-chip is-owner">
              <input type="checkbox" checked disabled />
              Вы<span>{number(p.target || 0)} ккал</span>
            </label>
            {p.members.map((m) => (
              <label className="setup-chip" key={m.id}>
                <input
                  type="checkbox"
                  checked={d.members.includes(m.id)}
                  onChange={(e) =>
                    change({
                      members: e.target.checked
                        ? [...d.members, m.id]
                        : d.members.filter((id) => id !== m.id),
                    })
                  }
                />
                {m.name}
                <span>· {number(m.dailyCalorieTarget)} ккал</span>
              </label>
            ))}
          </fieldset>
          {p.membersLoading && <p role="status">Загружаем семью…</p>}
          {p.membersError && (
            <Notice>
              Не удалось загрузить семью.{' '}
              {d.members.length
                ? 'Проверьте выбранные профили перед подбором.'
                : 'Можно составить меню только для вас.'}
              <button type="button" className="text-button" onClick={p.retryMembers}>
                Повторить загрузку семьи
              </button>
            </Notice>
          )}
          {!!missingMembers.length && (
            <Notice tone="warning">
              Выбранный профиль семьи больше недоступен.
              <button
                type="button"
                className="text-button"
                onClick={() =>
                  change({
                    members: d.members.filter((id) => !missingMembers.includes(id)),
                  })
                }
              >
                Убрать недоступные профили из выбора
              </button>
            </Notice>
          )}
          <Link className="text-link" to="/household">
            Добавить человека или изменить ограничения
          </Link>
          <p className="caption">
            Блюда общие, порции индивидуальные. Аллергии и исключения учитываются для всех.
          </p>
        </section>
        <section className="panel setup-section">
          <div className="setup-section-title">
            <CalendarDays size={20} />
            <h2>На сколько дней</h2>
          </div>
          <div className="setup-period">
            <label>
              Начало периода
              <input
                type="date"
                required
                value={d.from}
                onChange={(e) => change({ from: e.target.value })}
              />
            </label>
            <fieldset className="setup-presets">
              <legend className="sr-only">Длительность меню</legend>
              {[3, 5, 7].map((days) => (
                <button
                  type="button"
                  key={days}
                  className={`button ${d.days === days && !custom ? 'primary' : 'secondary'}`}
                  aria-pressed={d.days === days && !custom}
                  onClick={() => {
                    setCustom(false);
                    change({ days });
                  }}
                >
                  {days} {days === 3 ? 'дня' : 'дней'}
                </button>
              ))}
              <button
                type="button"
                className={`button ${custom ? 'primary' : 'secondary'}`}
                aria-pressed={custom}
                onClick={() => setCustom(true)}
              >
                Другой период
              </button>
            </fieldset>
            {custom && (
              <label>
                Дней
                <input
                  type="number"
                  min={1}
                  max={14}
                  required
                  value={d.days || ''}
                  onChange={(e) => change({ days: Number(e.target.value) })}
                />
              </label>
            )}
          </div>
          <p className="caption">
            {d.from && d.days >= 1 && d.days <= 14
              ? `${labelDate(d.from)} — ${labelDate(shiftDate(d.from, d.days - 1))} · все дни включены`
              : 'Выберите период от 1 до 14 дней'}
          </p>
        </section>
        <section className="panel setup-section">
          <div className="setup-section-title">
            <Store size={20} />
            <h2>Бюджет и магазины</h2>
          </div>
          <div className="setup-budget">
            <label>
              Бюджет на покупки, ₽
              <input
                type="number"
                min="0.01"
                step="0.01"
                value={d.budget}
                placeholder="Без бюджета"
                onChange={(e) => change({ budget: e.target.value })}
              />
            </label>
            <button
              type="button"
              className={`button ${!d.budget ? 'quiet' : 'secondary'}`}
              aria-pressed={!d.budget}
              onClick={() => change({ budget: '' })}
            >
              Без бюджета
            </button>
            <span className="caption">Общая сумма на {d.members.length + 1} чел.</span>
          </div>
          <div className="setup-stores-summary">
            <p>
              <strong>
                {p.chains
                  .filter((c) => d.chains.includes(c.id))
                  .map((c) => c.name)
                  .join(', ') || 'Выберите магазины'}
              </strong>
              <span> · {d.city || 'Город не указан'}</span>
            </p>
            <button
              type="button"
              className="text-button"
              aria-expanded={storesOpen}
              onClick={() => setStoresOpen(!storesOpen)}
            >
              {storesOpen ? 'Свернуть магазины' : 'Изменить магазины'}
            </button>
          </div>
          {(storesOpen || p.chainsError || missingChains.length > 0) && (
            <div className="setup-stores">
              <label>
                Город
                <input
                  required
                  maxLength={120}
                  value={d.city}
                  onChange={(e) => change({ city: e.target.value })}
                />
              </label>
              {p.chainsLoading ? (
                <Loading />
              ) : p.chainsError ? (
                <Notice>
                  Не удалось загрузить магазины.
                  <button type="button" className="text-button" onClick={p.retryChains}>
                    Повторить загрузку магазинов
                  </button>
                </Notice>
              ) : (
                <fieldset className="setup-choices">
                  <legend className="sr-only">Доступные сети</legend>
                  {p.chains.map((c) => (
                    <label className="setup-chip" key={c.id}>
                      <input
                        type="checkbox"
                        checked={d.chains.includes(c.id)}
                        onChange={(e) =>
                          change({
                            chains: e.target.checked
                              ? [...d.chains, c.id]
                              : d.chains.filter((id) => id !== c.id),
                          })
                        }
                      />
                      {c.name}
                    </label>
                  ))}
                </fieldset>
              )}
              {!!missingChains.length && (
                <Notice tone="warning">
                  Одна из выбранных сетей недоступна.
                  <button
                    type="button"
                    className="text-button"
                    onClick={() =>
                      change({
                        chains: d.chains.filter((id) => !missingChains.includes(id)),
                      })
                    }
                  >
                    Убрать недоступные сети из выбора
                  </button>
                </Notice>
              )}
            </div>
          )}
        </section>
        <details
          className="panel setup-advanced"
          open={p.advanced}
          onToggle={(e) => p.onAdvanced(e.currentTarget.open)}
        >
          <summary>
            <SlidersHorizontal size={18} />
            Дополнительные настройки
            <ChevronDown size={16} />
          </summary>
          <div className="setup-advanced-content">
            <div className="period-fields">
              {meals.map((meal) => (
                <label key={meal}>
                  {mealLabels[meal]}: до, мин
                  <input
                    type="number"
                    min={0}
                    max={240}
                    required
                    value={d.times[meal]}
                    onChange={(e) =>
                      change({
                        times: { ...d.times, [meal]: Number(e.target.value) },
                      })
                    }
                  />
                </label>
              ))}
            </div>
            <div className="period-fields">
              <label>
                Один рецепт: максимум раз
                <input
                  type="number"
                  min={1}
                  max={14}
                  required
                  value={d.repeat}
                  onChange={(e) => change({ repeat: Number(e.target.value) })}
                />
              </label>
              <label>
                Полных дней между повторами
                <input
                  type="number"
                  min={0}
                  max={14}
                  required
                  value={d.gap}
                  onChange={(e) => change({ gap: Number(e.target.value) })}
                />
              </label>
            </div>
            <div className="setup-switches">
              <label>
                <input
                  type="checkbox"
                  checked={d.pantry}
                  onChange={(e) => change({ pantry: e.target.checked })}
                />
                Учитывать запасы дома
              </label>
              <label>
                <input
                  type="checkbox"
                  checked={d.estimates}
                  onChange={(e) => change({ estimates: e.target.checked })}
                />
                Разрешить приблизительные цены
              </label>
              <label>
                <input
                  type="checkbox"
                  checked={d.split}
                  onChange={(e) => change({ split: e.target.checked })}
                />
                Покупки максимум в двух сетях
              </label>
            </div>
            {d.split && (
              <label>
                Минимальная экономия для второй сети, ₽
                <input
                  type="number"
                  min={0}
                  step="0.01"
                  required
                  value={d.saving}
                  onChange={(e) => change({ saving: Number(e.target.value) })}
                />
              </label>
            )}
            <Link className="text-link" to="/pantry">
              Проверить домашние запасы
            </Link>
          </div>
        </details>
        <p className="caption setup-rules">
          Завтрак до {d.times.BREAKFAST} мин ·{' '}
          {d.pantry ? 'учитываем запасы' : 'без домашних запасов'} ·{' '}
          {d.estimates ? 'цены могут быть приблизительными' : 'только подтверждённые цены'}
        </p>
      </fieldset>
      {p.invalid && (
        <p role="alert" className="form-error">
          {p.invalid}
        </p>
      )}
      <div className="setup-submit">
        <button
          className="button primary"
          type="submit"
          disabled={
            p.busy ||
            !p.online ||
            !p.target ||
            p.chainsLoading ||
            p.chainsError ||
            (p.membersLoading && d.members.length > 0) ||
            (p.membersError && d.members.length > 0) ||
            missingMembers.length > 0 ||
            missingChains.length > 0
          }
        >
          <Sparkles size={18} />
          {p.busy ? 'Составляем меню…' : p.editing ? 'Пересчитать меню' : 'Составить меню'}
        </button>
        {p.onCancel && (
          <button type="button" className="button secondary" disabled={p.busy} onClick={p.onCancel}>
            Отменить изменения
          </button>
        )}
      </div>
      {!p.online && <p role="status">Нет сети. Подбор станет доступен после подключения.</p>}
    </form>
  );
}
