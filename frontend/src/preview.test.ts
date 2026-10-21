import { afterEach, expect, it, vi } from 'vitest';
import { previewApi, previewFormErrors, previewSaveBody, warningText } from './preview';
import type { MenuPreview, PreviewOptions } from './preview';
afterEach(() => vi.unstubAllGlobals());
const options: PreviewOptions = {
  planDate: '2026-10-21',
  breakfastMaxPreparationTimeMin: 15,
  lunchMaxPreparationTimeMin: 45,
  dinnerMaxPreparationTimeMin: 45,
};
const result: MenuPreview = {
  planDate: options.planDate,
  dailyCalorieTarget: 2200,
  nutritionTotal: { calories: 2199.5, protein: 110, fat: 70, carbs: 280 },
  calorieDeviation: -0.5,
  withinTargetTolerance: true,
  warnings: [],
  entries: (['BREAKFAST', 'LUNCH', 'DINNER'] as const).map((mealType, i) => ({
    mealType,
    recipeId: `r${i}`,
    recipeName: `Блюдо ${i}`,
    portions: [0.5, 1.25, 2][i],
    preparationTimeMin: 15,
    nutrition: { calories: 700, protein: 30, fat: 20, carbs: 50 },
    lastPlannedDate: null,
    recipeFingerprint: String(i).repeat(64),
  })),
};
it('сохраняет ровно исходные options, цель, 3 строки, порции и fingerprints без питания/имён', () => {
  expect(previewSaveBody(options, result)).toEqual({
    options,
    dailyCalorieTarget: 2200,
    entries: result.entries.map((e) => ({
      mealType: e.mealType,
      recipeId: e.recipeId,
      portions: e.portions,
      recipeFingerprint: e.recipeFingerprint,
    })),
  });
  expect(previewSaveBody(options, result).entries).toHaveLength(3);
  expect(JSON.stringify(previewSaveBody(options, result))).not.toContain('nutrition');
});
it('оба POST используют свежий CSRF и cookie, preview поддерживает отмену', async () => {
  const mock = vi
    .fn()
    .mockResolvedValueOnce(
      Response.json({ token: 'a', headerName: 'X-CSRF', parameterName: '_csrf' }),
    )
    .mockResolvedValueOnce(Response.json(result))
    .mockResolvedValueOnce(
      Response.json({ token: 'b', headerName: 'X-CSRF', parameterName: '_csrf' }),
    )
    .mockResolvedValueOnce(Response.json({ id: 'plan', status: 'DRAFT' }));
  vi.stubGlobal('fetch', mock);
  const signal = new AbortController().signal;
  expect(await previewApi.generate(options, signal)).toEqual(result);
  await previewApi.save(options, result);
  expect(mock.mock.calls[1][0]).toBe('/api/me/meal-plans/preview');
  expect(mock.mock.calls[1][1]).toMatchObject({
    signal,
    credentials: 'same-origin',
    method: 'POST',
  });
  expect(JSON.parse(mock.mock.calls[1][1].body)).toEqual(options);
  expect(mock.mock.calls[3][0]).toBe('/api/me/meal-plans/preview/save');
  expect(mock.mock.calls[3][1].headers.get('X-CSRF')).toBe('b');
  expect(JSON.parse(mock.mock.calls[3][1].body)).toEqual(previewSaveBody(options, result));
});
it.each([400, 401, 403, 404, 409, 500])(
  'save%s не повторяется и не возвращает фиктивное меню',
  async (status) => {
    const mock = vi
      .fn()
      .mockResolvedValueOnce(
        Response.json({ token: 'a', headerName: 'X-CSRF', parameterName: '_csrf' }),
      )
      .mockResolvedValueOnce(new Response(null, { status }));
    vi.stubGlobal('fetch', mock);
    await expect(previewApi.save(options, result)).rejects.toMatchObject({ status });
    expect(mock).toHaveBeenCalledTimes(2);
  },
);
it('ошибка генерации не заменяется локальным подбором', async () => {
  const mock = vi
    .fn()
    .mockResolvedValueOnce(
      Response.json({ token: 'a', headerName: 'X-CSRF', parameterName: '_csrf' }),
    )
    .mockResolvedValueOnce(Response.json({ detail: 'Не хватает рецептов' }, { status: 409 }));
  vi.stubGlobal('fetch', mock);
  await expect(previewApi.generate(options)).rejects.toMatchObject({
    status: 409,
    message: 'Не хватает рецептов',
  });
});
it('дата и целые лимиты, включая 0, проверяются до запроса', () => {
  expect(previewFormErrors(options.planDate, ['0', '45', '2147483647'])).toEqual({});
  expect(previewFormErrors('2026-02-30', ['15', '45', '45'])).toHaveProperty('planDate');
  for (const value of ['', '-1', '1.5', '1e3', '2147483648'])
    expect(previewFormErrors(options.planDate, [value, '45', '45'])).toHaveProperty(
      'breakfastMaxPreparationTimeMin',
    );
});
it('предупреждения переведены, неизвестные не скрываются', () => {
  for (const code of ['TARGET_NOT_REACHED', 'RECENT_RECIPES_USED', 'CATALOGUE_SEARCH_LIMIT'])
    expect(warningText(code)).not.toContain(code);
  expect(warningText('NEW_WARNING')).toContain('NEW_WARNING');
});
