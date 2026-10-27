import { expect, it } from 'vitest';
import { kitchenChoices, toggleKitchen } from './kitchen';
const equipment = [
  { id: 1, code: 'PAN', name: 'Сковорода' },
  { id: 2, code: 'POT', name: 'Кастрюля' },
  { id: 3, code: 'OVEN', name: 'Духовка' },
  { id: 4, code: 'AIR_FRYER', name: 'Аэрогриль' },
];
it('groups basic cookware under one stove choice and preserves appliance IDs', () => {
  const c = kitchenChoices(equipment);
  expect(c.map((x) => x.name)).toEqual(['Плита', 'Духовка', 'Аэрогриль']);
  expect(c[0].ids).toEqual([1, 2]);
  expect(toggleKitchen([3], [1, 2])).toEqual([3, 1, 2]);
  expect(toggleKitchen([3, 1, 2], [1, 2])).toEqual([3]);
});
it('preserves independent and legacy equipment choices without duplicates', () => {
  expect(toggleKitchen([1, 3], [1, 2])).toEqual([3]);
  expect(toggleKitchen([3, 4], [1, 2])).toEqual([3, 4, 1, 2]);
  expect(kitchenChoices([])).toEqual([]);
});
