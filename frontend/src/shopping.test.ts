import { afterEach, describe, expect, it, vi } from 'vitest';
import { calendarDay, periodErrors, readShoppingNeeds } from './shopping';
afterEach(() => vi.unstubAllGlobals());
describe('Период покупок', () => {
  it('один день разрешён, обе границы включены', () => {
    expect(periodErrors('2026-10-18', '2026-10-18')).toEqual({});
  });
  it('31 день разрешён, 32 дня отклоняются', () => {
    expect(periodErrors('2026-01-01', '2026-01-31')).toEqual({});
    expect(periodErrors('2026-01-01', '2026-02-01')).toHaveProperty('to');
  });
  it('корректно считает границу месяца и года в UTC', () => {
    expect(periodErrors('2026-12-17', '2027-01-16')).toEqual({});
    expect(periodErrors('2026-12-17', '2027-01-17')).toHaveProperty('to');
    expect(periodErrors('2026-03-01', '2026-03-31')).toEqual({});
  });
  it('обратный период отклоняется', () => {
    expect(periodErrors('2026-10-19', '2026-10-18')).toHaveProperty('to');
  });
  it.each(['', '2026-02-29', '2026-02-30', '2026-13-01', '2026-1-1', 'invalid'])(
    'не принимает дату %s',
    (date) => {
      expect(calendarDay(date)).toBeNull();
      expect(periodErrors(date, '2026-10-18')).toHaveProperty('from');
    },
  );
  it('принимает високосный день', () => {
    expect(calendarDay('2024-02-29')).not.toBeNull();
  });
});
describe('API списка', () => {
  it('передаёт даты и AbortSignal, сохраняет количества/порядок ответа', async () => {
    const response = {
      from: '2026-10-18',
      to: '2026-10-19',
      containsDrafts: true,
      items: [
        { productId: 2, productName: 'Рис', quantityG: 1250.5 },
        { productId: 1, productName: 'Рис', quantityG: 100 },
      ],
    };
    const mock = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify(response), { headers: { 'Content-Type': 'application/json' } }),
      );
    vi.stubGlobal('fetch', mock);
    const controller = new AbortController();
    expect(await readShoppingNeeds(response.from, response.to, controller.signal)).toEqual(
      response,
    );
    expect(mock.mock.calls[0][0]).toBe('/api/me/shopping-list?from=2026-10-18&to=2026-10-19');
    expect(mock.mock.calls[0][1]).toMatchObject({
      signal: controller.signal,
      credentials: 'same-origin',
      cache: 'no-store',
    });
    expect(mock).toHaveBeenCalledTimes(1);
  });
  it.each([400, 404, 500])('не подменяет HTTP%s пустым списком', async (status) => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          new Response(JSON.stringify({ detail: 'Список недоступен' }), {
            status,
            headers: { 'Content-Type': 'application/problem+json' },
          }),
        ),
    );
    await expect(readShoppingNeeds('2026-10-18', '2026-10-19')).rejects.toMatchObject({
      status,
      message: 'Список недоступен',
    });
  });
});
