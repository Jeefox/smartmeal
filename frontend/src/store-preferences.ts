export const CITY_KEY = 'smartmeal.v2.preferences.city';
export function readCity() {
  try {
    const value = JSON.parse(localStorage.getItem(CITY_KEY) ?? 'null');
    return typeof value === 'string' && value.trim().length <= 120 ? value.trim() : '';
  } catch {
    return '';
  }
}
export function saveCity(city: string) {
  try {
    localStorage.setItem(CITY_KEY, JSON.stringify(city.trim()));
  } catch {
    /* Search stays usable without browser storage. */
  }
}
