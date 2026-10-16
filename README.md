# SmartMeal

Учебный планировщик питания и покупок на Java 21 и Spring Boot 4.1.1.

## Реализовано

- Каталог продуктов: создание, чтение, редактирование, поиск и пагинация.
- Справочник категорий.
- Создание и чтение рецептов с упорядоченным составом.
- Валидация запросов и ответы об ошибках через ProblemDetail.
- Расчёт КБЖУ рецепта и порции (пока не подключён к API).
- PostgreSQL, Flyway V1/V2 и unit-тесты калькулятора.

## Локальный запуск

Нужны JDK 21, Docker и Docker Compose.

Скопируйте `.env.example` в `.env` и задайте собственный `POSTGRES_PASSWORD`.
Spring Boot и Compose читают `.env` из корня проекта; запуск из IDE должен использовать этот рабочий каталог.

```bash
docker compose up -d --wait
bash mvnw spring-boot:run
```

API: http://localhost:8080/api/products, /api/product-categories, /api/recipes.
PostgreSQL: localhost:5433, база/пользователь smartmeal. Пароль хранится только в локальном `.env`.
Изменение `.env` не меняет пароль уже созданной БД: для существующего тома пароль меняется отдельно в PostgreSQL.
Схемой управляет Flyway; применённые миграции не редактируются.

## Unit-тесты калькулятора

```bash
bash mvnw -Dtest=NutritionCalculatorTest test
```

Тесты не требуют Spring-контекста и PostgreSQL.
