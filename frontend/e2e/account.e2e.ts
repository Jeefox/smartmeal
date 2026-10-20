import { test, expect } from '@playwright/test';
import type { Page, Route } from '@playwright/test';
async function fixture(page: Page) {
  const state = {
    signed: false,
    network: false,
    expired: false,
    registerConflict: false,
    estimateIncomplete: false,
    csrfFailure: false,
    profile: {
      userId: 'user-one',
      dailyCalorieTarget: null as number | null,
      age: null as number | null,
      sex: null as string | null,
      heightCm: null as number | null,
      weightKg: null as number | null,
      activityLevel: null as string | null,
      goal: null as string | null,
    },
    calls: [] as {
      path: string;
      method: string;
      body: any;
      csrf: string | null;
      cookie: string | null;
    }[],
  };
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.route('**/logout', async (route) => {
    state.signed = false;
    await route.fulfill({ status: 302, headers: { location: '/login?logout' }, body: '' });
  });
  await page.route('**/api/**', async (route: Route) => {
    const req = route.request();
    const path = new URL(req.url()).pathname;
    const method = req.method();
    const headers = await req.allHeaders();
    const body = req.postData()
      ? method === 'POST' && path.endsWith('/login')
        ? Object.fromEntries(new URLSearchParams(req.postData()!))
        : req.postDataJSON()
      : null;
    state.calls.push({
      path,
      method,
      body,
      csrf: headers['x-csrf-token'] || null,
      cookie: headers.cookie || null,
    });
    const json = (value: unknown, status = 200, extra: Record<string, string> = {}) =>
      route.fulfill({
        status,
        contentType: 'application/json',
        headers: extra,
        body: JSON.stringify(value),
      });
    if (path === '/api/auth/csrf')
      return json({
        token: state.signed ? 'fresh-after-login' : 'before-login',
        headerName: 'X-CSRF-TOKEN',
        parameterName: '_csrf',
      });
    if (path === '/api/auth/register')
      return state.registerConflict
        ? json({ detail: 'Email уже занят' }, 409)
        : json(state.profile, 201);
    if (path === '/api/auth/login') {
      if (body.password !== 'secret123') return route.fulfill({ status: 401, body: '' });
      state.signed = true;
      return route.fulfill({
        status: 204,
        headers: { 'set-cookie': 'JSESSIONID=test-session; Path=/; HttpOnly; SameSite=Lax' },
      });
    }
    if (path === '/api/me/profile' && method === 'GET') {
      if (state.network) return route.abort('failed');
      return state.signed && !state.expired
        ? json(state.profile)
        : route.fulfill({ status: 401, body: '' });
    }
    if (!state.signed || state.expired) return route.fulfill({ status: 401, body: '' });
    if (method === 'PUT' && (state.csrfFailure || headers['x-csrf-token'] !== 'fresh-after-login'))
      return route.fulfill({ status: 403, body: '' });
    if (path === '/api/me/profile' && method === 'PUT') {
      state.profile = { ...state.profile, ...body };
      return json(state.profile);
    }
    if (path === '/api/me/profile/daily-calorie-target') {
      state.profile.dailyCalorieTarget = body.dailyCalorieTarget;
      return json(state.profile);
    }
    if (path === '/api/me/energy-estimate')
      return state.estimateIncomplete
        ? json({ detail: 'Анкета не заполнена' }, 409)
        : json({ restingCalories: 1780, maintenanceCalories: 3204, recommendedCalories: 2884 });
    return json({ content: [], page: 0, size: 9, totalElements: 0, totalPages: 0 });
  });
  return state;
}
async function login(page: Page) {
  await page.goto('/login');
  await page.getByLabel('Email', { exact: true }).fill('user@example.test');
  await page.getByLabel('Пароль', { exact: true }).fill('secret123');
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  await expect(page).toHaveURL(/welcome|profile|recipes/);
}
async function survey(page: Page) {
  await page.goto('/onboarding');
  await page.getByLabel('Возраст, лет').fill('30');
  await page.getByRole('combobox', { name: 'Пол', exact: true }).selectOption('MALE');
  await page.getByLabel('Рост, см').fill('180');
  await page.getByLabel('Вес, кг').fill('80.5');
  await page.getByRole('button', { name: 'Продолжить', exact: true }).click();
  await page.locator('input[value="MODERATE"]').check();
  await page.getByRole('button', { name: 'Продолжить', exact: true }).click();
  await page.locator('input[value="LOSE_WEIGHT"]').check();
  await page.getByRole('button', { name: 'Сохранить анкету и рассчитать', exact: true }).click();
}
test('регистрация без калорий и без автоматического входа; 409 виден', async ({ page }) => {
  const state = await fixture(page);
  await page.goto('/register');
  await expect(page.locator('[name=dailyCalorieTarget]')).toHaveCount(0);
  await page.getByLabel('Email', { exact: true }).fill('new@example.test');
  await page.getByLabel('Пароль', { exact: true }).fill('secret123');
  state.registerConflict = true;
  await page.getByRole('button', { name: 'Зарегистрироваться', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Email уже занят');
  state.registerConflict = false;
  await page.getByLabel('Пароль', { exact: true }).fill('secret123');
  await page.getByRole('button', { name: 'Зарегистрироваться', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Аккаунт создан', exact: true })).toBeVisible();
  expect(state.calls.some((c) => c.path.endsWith('/login'))).toBe(false);
});
test('вход, пропуск опроса и каталог без анкеты; сессионная cookie', async ({ page }) => {
  const state = await fixture(page);
  await login(page);
  await expect(page.getByRole('link', { name: 'Позже, открыть рецепты' })).toBeVisible();
  await page.getByRole('link', { name: 'Позже, открыть рецепты' }).click();
  await expect(page.getByRole('heading', { name: 'Что приготовим?' })).toBeVisible();
  const calls = state.calls;
  const index = calls.findIndex((c) => c.path.endsWith('/login'));
  expect(calls[index + 1].path).toBe('/api/auth/csrf');
  await expect
    .poll(() =>
      calls.some((c) => c.path === '/api/recipes' && c.cookie?.includes('JSESSIONID=test-session')),
    )
    .toBe(true);
});
test('опрос сохраняет анкету, расчёт не сохраняет цель, рекомендация подтверждается отдельно', async ({
  page,
}) => {
  const state = await fixture(page);
  await login(page);
  await survey(page);
  await expect(page.getByText('Рекомендация под цель', { exact: true })).toBeVisible();
  expect(state.profile.dailyCalorieTarget).toBeNull();
  expect(state.calls.filter((c) => c.method === 'PUT').map((c) => c.path)).toEqual([
    '/api/me/profile',
  ]);
  const save = state.calls.find((c) => c.path === '/api/me/profile' && c.method === 'PUT')!;
  expect(save.csrf).toBe('fresh-after-login');
  expect(save.body).not.toHaveProperty('dailyCalorieTarget');
  await page.getByRole('button', { name: /Принять рекомендацию/ }).click();
  await expect(page.locator('.saved-target')).toContainText('2 884');
  expect(state.profile.dailyCalorieTarget).toBe(2884);
  await page.getByLabel('Своя суточная цель, ккал').fill('2200');
  await page.getByRole('button', { name: 'Подтвердить свою цель', exact: true }).click();
  await expect(page.locator('.saved-target')).toContainText('2 200');
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Ваш день — ваш ориентир' })).toBeVisible();
  await page.goto('/profile');
  await expect(page.locator('.saved-target')).toContainText('2 200');
  const stored = await page.evaluate(() => JSON.stringify(localStorage));
  expect(stored).not.toContain('user-one');
  expect(stored).not.toContain('secret123');
});
test('новая анкета не перезаписывает старую цель, 409 расчёта понятен', async ({ page }) => {
  const state = await fixture(page);
  state.profile.dailyCalorieTarget = 2200;
  state.estimateIncomplete = true;
  await login(page);
  await survey(page);
  await expect(page.getByRole('alert')).toContainText('Анкета не заполнена');
  expect(state.profile.dailyCalorieTarget).toBe(2200);
  expect(state.calls.filter((c) => c.path.endsWith('daily-calorie-target'))).toHaveLength(0);
});
test('401 входа, 403 записи и сетевой сбой не маскируются успехом', async ({ page }) => {
  const state = await fixture(page);
  await page.goto('/login');
  await page.getByLabel('Email', { exact: true }).fill('user@example.test');
  await page.getByLabel('Пароль', { exact: true }).fill('wrong');
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Неверный email или пароль');
  await page.getByLabel('Пароль', { exact: true }).fill('secret123');
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  await expect(page).toHaveURL(/\/welcome$/);
  await page.goto('/profile');
  await page.getByLabel('Своя суточная цель, ккал').fill('2200');
  state.csrfFailure = true;
  await page.getByRole('button', { name: 'Подтвердить свою цель', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('CSRF');
  expect(state.calls.filter((c) => c.path.endsWith('daily-calorie-target'))).toHaveLength(1);
  state.network = true;
  await page.reload();
  await expect(page.locator('.error-state')).toContainText('Не удалось');
  await expect(page).toHaveURL(/profile/);
});
test('истёкшая сессия требует входа, без показа кэша другого аккаунта', async ({ page }) => {
  const state = await fixture(page);
  await login(page);
  state.expired = true;
  await page.goto('/profile');
  await expect(page).toHaveURL(/login/);
  await expect(page.locator('.saved-target')).toHaveCount(0);
});
test('мобильный опрос и результат на 360/390/768/1440 без переполнения', async ({ page }) => {
  await fixture(page);
  await login(page);
  for (const width of [360, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/onboarding');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
  }
  await page.setViewportSize({ width: 360, height: 900 });
  await survey(page);
  await expect(page.locator('.estimate-grid')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole('heading', { name: 'Ваш предварительный ориентир', exact: true }).click();
  await page.locator('.toast').waitFor({ state: 'hidden' });
  await page.screenshot({ path: 'test-results/account-result-mobile.png', fullPage: true });
});

test('выход использует подтверждённый /logout и проверяет завершение сессии', async ({ page }) => {
  const state = await fixture(page);
  state.profile.dailyCalorieTarget = 2200;
  await login(page);
  await page.goto('/profile');
  await page.getByRole('button', { name: 'Выйти', exact: true }).click();
  await expect(page).toHaveURL(/login/);
  expect(state.signed).toBe(false);
  await page.goBack();
  await expect(page).toHaveURL(/login/);
  await expect(page.locator('.saved-target')).toHaveCount(0);
});
