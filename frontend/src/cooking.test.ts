import { expect, it } from 'vitest';
import { cookingGroups } from './cooking';
it('groups the same saved recipe across days and keeps other recipes separate', () => {
  const base = {
    ref: { entryId: 'a', componentId: 'side' },
    date: '2035-01-03',
    mealType: 'DINNER',
    recipeName: 'Гречка',
    fingerprint: 'same',
    cookedG: 400,
  };
  const groups = cookingGroups([
    base,
    { ...base, ref: { entryId: 'b', componentId: 'side' }, date: '2035-01-01', cookedG: 350 },
    { ...base, fingerprint: 'different-recipe', recipeName: 'Рис' },
  ]);
  expect(groups).toHaveLength(2);
  expect(groups[0].cookedG).toBe(750);
  expect(groups[0].entries[0].date).toBe('2035-01-01');
});
