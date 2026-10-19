import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, request } from './api';
afterEach(() => vi.unstubAllGlobals());
describe('API-клиент', () => {
  it('принимает краткий DTO preparationTime без состава и инструкций', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            content: [{ id: 'id', name: 'Рис', description: '', preparationTime: 20, servings: 2 }],
            page: 0,
            size: 9,
            totalElements: 1,
            totalPages: 1,
          }),
          { headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    );
    const page = await api.recipes(new URLSearchParams());
    expect(page.content[0].preparationTimeMin).toBe(20);
    expect(page.content[0]).not.toHaveProperty('ingredients');
  });
  it('не выдаёт сломанный контракт за время 0 минут', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ content: [{ id: 'id', name: 'Рис' }] }), {
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );
    await expect(api.recipes(new URLSearchParams())).rejects.toMatchObject({ status: 502 });
  });
  it('сохраняет вложенные ошибки полей из ProblemDetail', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            detail: 'Проверьте массу',
            errors: [{ field: 'ingredients[0].quantityG', message: 'Больше нуля' }],
          }),
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    );
    await expect(request('/recipes')).rejects.toMatchObject({
      status: 400,
      fields: [{ field: 'ingredients[0].quantityG', message: 'Больше нуля' }],
    });
  });
  it('объясняет конфликт, не маскируя его успешным сохранением', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          new Response('{}', { status: 409, headers: { 'Content-Type': 'application/json' } }),
        ),
    );
    await expect(request('/recipes', { method: 'PUT' })).rejects.toBeInstanceOf(ApiError);
  });
  it('отмена устаревшего запроса не превращается в ошибку сети', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new DOMException('Aborted', 'AbortError')));
    await expect(request('/products')).rejects.toMatchObject({ name: 'AbortError' });
  });
});

const csrfResponse = (token = 'current') =>
  new Response(JSON.stringify({ token, headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' }), {
    headers: { 'Content-Type': 'application/json' },
  });
const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
describe('Сессия и CSRF', () => {
  it('вход отправляет form-urlencoded и обновляет CSRF после 204', async () => {
    const mock = vi
      .fn()
      .mockResolvedValueOnce(csrfResponse('before'))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(csrfResponse('after'));
    vi.stubGlobal('fetch', mock);
    await api.login('user@example.test', 'p&ss=word');
    expect(mock.mock.calls.map((call) => call[0])).toEqual([
      '/api/auth/csrf',
      '/api/auth/login',
      '/api/auth/csrf',
    ]);
    const init = mock.mock.calls[1][1] as RequestInit;
    const headers = new Headers(init.headers);
    expect(headers.get('Content-Type')).toContain('application/x-www-form-urlencoded');
    expect(headers.get('X-CSRF-TOKEN')).toBe('before');
    expect(init.credentials).toBe('same-origin');
    expect(init.body?.toString()).toBe('email=user%40example.test&password=p%26ss%3Dword');
  });
  it('регистрация передаёт только email/password и не выполняет вход', async () => {
    const mock = vi
      .fn()
      .mockResolvedValueOnce(csrfResponse())
      .mockResolvedValueOnce(jsonResponse({ userId: 'u', dailyCalorieTarget: null }, 201));
    vi.stubGlobal('fetch', mock);
    await api.register(' user@example.test ', 'secret123');
    expect(mock.mock.calls).toHaveLength(2);
    expect(mock.mock.calls[1][0]).toBe('/api/auth/register');
    expect(JSON.parse(mock.mock.calls[1][1].body)).toEqual({
      email: 'user@example.test',
      password: 'secret123',
    });
  });
  it('сохраняет текст 409 о занятом email', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(csrfResponse())
        .mockResolvedValueOnce(
          jsonResponse({ detail: 'Пользователь с таким email уже зарегистрирован' }, 409),
        ),
    );
    await expect(api.register('a@b.test', 'secret123')).rejects.toMatchObject({
      status: 409,
      message: 'Пользователь с таким email уже зарегистрирован',
    });
  });
  it('не повторяет изменяющий запрос после пустого 403', async () => {
    const mock = vi
      .fn()
      .mockResolvedValueOnce(csrfResponse())
      .mockResolvedValueOnce(new Response(null, { status: 403 }));
    vi.stubGlobal('fetch', mock);
    await expect(api.updateCalorieTarget(2200)).rejects.toMatchObject({ status: 403 });
    expect(mock.mock.calls).toHaveLength(2);
  });
  it('пустой 401 входа имеет понятное сообщение', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(csrfResponse())
        .mockResolvedValueOnce(new Response(null, { status: 401 })),
    );
    await expect(api.login('u@x.test', 'incorrect')).rejects.toMatchObject({
      status: 401,
      message: 'Неверный email или пароль.',
    });
  });
  it('ошибка сети отличается от отсутствия авторизации', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network')));
    await expect(api.profile()).rejects.toMatchObject({ status: 0 });
  });
  it('расчёт запрашивает результат сервера и не сохраняет цель', async () => {
    const result = { restingCalories: 1780, maintenanceCalories: 3204, recommendedCalories: 2884 };
    const mock = vi.fn().mockResolvedValue(jsonResponse(result));
    vi.stubGlobal('fetch', mock);
    expect(await api.energyEstimate()).toEqual(result);
    expect(mock.mock.calls).toHaveLength(1);
    expect(mock.mock.calls[0][0]).toBe('/api/me/energy-estimate');
  });
  it('цель сохраняется отдельным PUT с актуальным CSRF', async () => {
    const mock = vi
      .fn()
      .mockResolvedValueOnce(csrfResponse())
      .mockResolvedValueOnce(jsonResponse({ dailyCalorieTarget: 2200 }));
    vi.stubGlobal('fetch', mock);
    await api.updateCalorieTarget(2200);
    expect(mock.mock.calls[1][0]).toBe('/api/me/profile/daily-calorie-target');
    expect(mock.mock.calls[1][1].method).toBe('PUT');
    expect(JSON.parse(mock.mock.calls[1][1].body)).toEqual({ dailyCalorieTarget: 2200 });
  });
});
