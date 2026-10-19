import type {
  Page,
  Product,
  ProductCategory,
  Recipe,
  RecipeInput,
  RecipeSummary,
  UserProfile,
  UserProfileInput,
  EnergyEstimate,
} from './types';
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public fields: { field: string; message: string }[] = [],
  ) {
    super(message);
    this.name = 'ApiError';
  }
}
export type CsrfToken = { token: string; headerName: string; parameterName: string };
export async function csrfHeaders(signal?: AbortSignal): Promise<Record<string, string>> {
  const token = await request<CsrfToken>('/auth/csrf', { signal });
  if (!token.token || !token.headerName)
    throw new ApiError(502, 'Сервер не вернул CSRF-токен. Обновите страницу и повторите действие.');
  return { [token.headerName]: token.token };
}
export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const method = (options.method || 'GET').toUpperCase();
  const headers = new Headers(options.headers);
  headers.set('Accept', 'application/json');
  if (options.body && !headers.has('Content-Type'))
    headers.set(
      'Content-Type',
      options.body instanceof URLSearchParams
        ? 'application/x-www-form-urlencoded;charset=UTF-8'
        : 'application/json',
    );
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const csrf = await csrfHeaders(options.signal ?? undefined);
    for (const [name, value] of Object.entries(csrf)) headers.set(name, value);
  }
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      ...options,
      credentials: 'same-origin',
      cache: 'no-store',
      headers,
    });
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') throw error;
    throw new ApiError(0, 'Не удалось связаться с сервером. Проверьте сеть и запуск Spring Boot.');
  }
  const isJson = (response.headers.get('content-type') || '').includes('json');
  let body = null;
  if (isJson && response.status !== 204) {
    try {
      body = await response.json();
    } catch {
      if (response.ok) throw new ApiError(502, 'Сервер вернул некорректный JSON.');
    }
  }
  if (!response.ok) {
    if (response.status === 401 && path !== '/auth/login' && typeof window !== 'undefined')
      window.dispatchEvent(new Event('smartmeal:unauthorized'));
    const fallback =
      response.status === 401
        ? path === '/auth/login'
          ? 'Неверный email или пароль.'
          : 'Войдите в аккаунт. Возможно, срок сессии истёк.'
        : response.status === 403
          ? 'Действие не разрешено. Обновите страницу и повторите вручную: возможно, CSRF-токен устарел.'
          : response.status === 409
            ? 'Изменение не выполнено из-за конфликта данных.'
            : `Запрос не выполнен (${response.status}).`;
    throw new ApiError(
      response.status,
      body?.detail || body?.title || fallback,
      Array.isArray(body?.errors) ? body.errors : [],
    );
  }
  if (response.status === 204) return undefined as T;
  if (!body) throw new ApiError(502, 'Сервер вернул неожиданный ответ. Проверьте адрес API.');
  return body as T;
}
type RecipeSummaryWire = Omit<RecipeSummary, 'preparationTimeMin'> & {
  preparationTimeMin?: number;
  preparationTime?: number;
};
export const api = {
  logout: async () => {
    const headers = await csrfHeaders();
    let response: Response;
    try {
      response = await fetch('/logout', {
        method: 'POST',
        credentials: 'same-origin',
        headers,
        redirect: 'manual',
        cache: 'no-store',
      });
    } catch {
      throw new ApiError(
        0,
        'Нет связи с сервером. Выход не подтверждён; повторите после восстановления сети.',
      );
    }
    if (!response.ok && response.type !== 'opaqueredirect')
      throw new ApiError(
        response.status,
        'Сервер не подтвердил выход. Повторите действие вручную.',
      );
    // Verified Spring default: 302 /login?logout. Confirm invalidation with a separate request.
    try {
      await request<UserProfile>('/me/profile');
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) return;
      throw error;
    }
    throw new ApiError(502, 'Сессия всё ещё активна. Выход не подтверждён.');
  },
  register: (email: string, password: string) =>
    request<UserProfile>('/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email: email.trim(), password }),
    }),
  login: async (email: string, password: string) => {
    await request<void>('/auth/login', {
      method: 'POST',
      body: new URLSearchParams({ email: email.trim(), password }),
    });
    // Spring rotates the session/CSRF token after authentication. Never reuse the login token.
    await csrfHeaders();
  },
  profile: (signal?: AbortSignal) => request<UserProfile>('/me/profile', { signal }),
  updateProfile: (input: UserProfileInput) =>
    request<UserProfile>('/me/profile', { method: 'PUT', body: JSON.stringify(input) }),
  updateCalorieTarget: (dailyCalorieTarget: number) =>
    request<UserProfile>('/me/profile/daily-calorie-target', {
      method: 'PUT',
      body: JSON.stringify({ dailyCalorieTarget }),
    }),
  energyEstimate: () => request<EnergyEstimate>('/me/energy-estimate'),

  recipes: async (query: URLSearchParams, signal?: AbortSignal): Promise<Page<RecipeSummary>> => {
    const page = await request<Page<RecipeSummaryWire>>(`/recipes?${query}`, { signal });
    return {
      ...page,
      content: page.content.map((item) => {
        const minutes = item.preparationTimeMin ?? item.preparationTime;
        if (typeof minutes !== 'number' || !Number.isFinite(minutes))
          throw new ApiError(
            502,
            'В кратком DTO рецепта отсутствует время приготовления. Проверьте контракт списка.',
          );
        return { ...item, preparationTimeMin: minutes };
      }),
    };
  },
  recipe: (id: string, signal?: AbortSignal) =>
    request<Recipe>(`/recipes/${encodeURIComponent(id)}`, { signal }),
  createRecipe: (input: RecipeInput) =>
    request<Recipe>('/recipes', { method: 'POST', body: JSON.stringify(input) }),
  updateRecipe: (id: string, input: RecipeInput) =>
    request<Recipe>(`/recipes/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify(input),
    }),
  products: (params: URLSearchParams, signal?: AbortSignal) =>
    request<Page<Product>>(`/products?${params}`, { signal }),
  categories: (signal?: AbortSignal) =>
    request<ProductCategory[]>('/product-categories', { signal }),
};
