import { request, ApiError } from './api';
export type KitchenChoice = { id: number; code: string; name: string };
export type Kitchen = { configured: boolean; equipmentIds: number[]; available: KitchenChoice[] };
export const kitchenKey = (user: string) => ['kitchen', user] as const;
export const kitchenApi = {
  get: async (signal?: AbortSignal) => {
    const value = await request<Kitchen>('/me/kitchen', { signal });
    if (
      typeof value.configured !== 'boolean' ||
      !Array.isArray(value.equipmentIds) ||
      !Array.isArray(value.available)
    )
      throw new ApiError(
        502,
        'Не удалось получить настройки кухни. Обновите приложение и повторите.',
      );
    return value;
  },
  save: (equipmentIds: number[]) =>
    request<Kitchen>('/me/kitchen', { method: 'PUT', body: JSON.stringify({ equipmentIds }) }),
};
export function kitchenChoices(available: KitchenChoice[]) {
  const stove = available.filter((x) => x.code === 'PAN' || x.code === 'POT');
  return [
    ...(stove.length
      ? [
          {
            key: 'STOVE',
            name: 'Плита',
            description: 'Сковорода и кастрюля',
            ids: stove.map((x) => x.id),
          },
        ]
      : []),
    ...available
      .filter((x) => x.code !== 'PAN' && x.code !== 'POT')
      .map((x) => ({
        key: x.code,
        name: x.name,
        description:
          x.code === 'AIR_FRYER'
            ? 'Горячий воздух'
            : x.code === 'MICROWAVE'
              ? 'Быстрый разогрев и готовка'
              : '',
        ids: [x.id],
      })),
  ];
}
export function toggleKitchen(selected: number[], ids: number[]) {
  const active = ids.some((id) => selected.includes(id));
  return active ? selected.filter((id) => !ids.includes(id)) : [...new Set([...selected, ...ids])];
}
