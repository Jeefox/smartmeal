import { afterEach, expect, it, vi } from 'vitest';
import { CITY_KEY, readCity, saveCity } from './store-preferences';
import { recipeCover } from './recipe-covers';
afterEach(() => vi.unstubAllGlobals());
it('сохраняет только город как настройку устройства', () => {
  const data = new Map<string, string>();
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => data.get(k) ?? null,
    setItem: (k: string, v: string) => data.set(k, v),
  });
  expect(readCity()).toBe('');
  saveCity(' Тула ');
  expect(readCity()).toBe('Тула');
  expect([...data.keys()]).toEqual([CITY_KEY]);
});
it('повреждённое/недоступное хранилище не ломает поиск', () => {
  vi.stubGlobal('localStorage', {
    getItem: () => '{broken',
    setItem: () => {
      throw Error('blocked');
    },
  });
  expect(readCity()).toBe('');
  expect(() => saveCity('Тула')).not.toThrow();
  vi.stubGlobal('localStorage', { getItem: () => JSON.stringify({ city: 'Тула' }) });
  expect(readCity()).toBe('');
});
it('локальная фотография имеет атрибуцию, а неизвестный рецепт — заглушку', () => {
  expect(recipeCover({ id: '1eb55deb-2b9e-50be-a46c-e5cbb032c585' })).toMatchObject({
    src: '/images/recipes/omelette-960.webp',
    generated: false,
    credit: { author: 'Tom K (JacquesDemien)', license: 'CC0 1.0' },
  });
  expect(recipeCover({ id: 'unknown' })).toBeNull();
  expect(recipeCover({ id: 'unknown', image: '/custom.jpg' })).toMatchObject({
    src: '/custom.jpg',
    generated: false,
  });
});
