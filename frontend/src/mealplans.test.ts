import { afterEach, describe, expect, it, vi } from 'vitest';
import { addRecipeToDay, mealPlanApi, validPortions } from './mealplans';
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const csrf = () => json({ token: 'fresh', headerName: 'X-CSRF', parameterName: '_csrf' });
afterEach(() => vi.unstubAllGlobals());
describe('API меню', () => {
  it('404 дня означает отсутствие меню, а сеть — ошибку', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 404 })));
    expect(await mealPlanApi.find('2026-10-18')).toBeNull();
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')));
    await expect(mealPlanApi.find('2026-10-18')).rejects.toMatchObject({ status: 0 });
  });
  it('создаёт черновик с датой и актуальным CSRF', async () => {
    const mock = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(json({ id: 'plan' }, 201));
    vi.stubGlobal('fetch', mock);
    await mealPlanApi.create('2026-10-18');
    const [url, init] = mock.mock.calls[1];
    expect(url).toBe('/api/me/meal-plans');
    expect(JSON.parse(init.body)).toEqual({ planDate: '2026-10-18' });
    expect(new Headers(init.headers).get('X-CSRF')).toBe('fresh');
  });
  it('не создаёт второе меню при добавлении в существующее', async () => {
    const mock = vi
      .fn()
      .mockResolvedValueOnce(json({ id: 'plan', status: 'DRAFT' }))
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(json({ id: 'entry' }, 201));
    vi.stubGlobal('fetch', mock);
    await addRecipeToDay('2026-10-18', 'recipe', 'LUNCH', 1.5);
    expect(mock.mock.calls.map((c) => c[0])).toEqual([
      '/api/me/meal-plans?date=2026-10-18',
      '/api/auth/csrf',
      '/api/me/meal-plans/plan/entries',
    ]);
    expect(JSON.parse(mock.mock.calls[2][1].body)).toEqual({
      recipeId: 'recipe',
      mealType: 'LUNCH',
      portions: 1.5,
    });
  });
  it('не отправляет добавление в подтверждённое меню', async () => {
    const mock = vi.fn().mockResolvedValue(json({ id: 'plan', status: 'CONFIRMED' }));
    vi.stubGlobal('fetch', mock);
    await expect(addRecipeToDay('2026-10-18', 'recipe', 'DINNER', 1)).rejects.toMatchObject({
      status: 409,
    });
    expect(mock).toHaveBeenCalledTimes(1);
  });
  it('после 409 создания читает существующее меню и не повторяет POST', async () => {
    const mock = vi
      .fn()
      .mockResolvedValueOnce(new Response(null, { status: 404 }))
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(json({ detail: 'Меню существует' }, 409))
      .mockResolvedValueOnce(json({ id: 'plan', status: 'DRAFT' }))
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(json({ id: 'entry' }, 201));
    vi.stubGlobal('fetch', mock);
    await addRecipeToDay('2026-10-18', 'recipe', 'BREAKFAST', 0.5);
    expect(mock.mock.calls.filter((c) => c[1]?.method === 'POST').map((c) => c[0])).toEqual([
      '/api/me/meal-plans',
      '/api/me/meal-plans/plan/entries',
    ]);
  });
  it('подтверждает без JSON-тела и удаляет с пустым 204', async () => {
    const mock = vi
      .fn()
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(json({ status: 'CONFIRMED' }))
      .mockResolvedValueOnce(csrf())
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', mock);
    await mealPlanApi.confirm('plan');
    expect(mock.mock.calls[1][1].body).toBeUndefined();
    expect(await mealPlanApi.remove('plan', 'entry')).toBeUndefined();
    expect(mock.mock.calls[3][1].method).toBe('DELETE');
  });
  it.each(['0', '-1', '1.234', '10000', '', '1e2'])(
    'не принимает некорректные порции %s',
    (value) => expect(validPortions(value)).toBe(false),
  );
  it.each(['0.01', '0.5', '1.5', '9999.99'])('принимает порции %s', (value) =>
    expect(validPortions(value)).toBe(true),
  );
});
