#!/usr/bin/env python3
"""Import the curated local recipe catalogue atomically; never overwrite existing recipes.

Run without --apply for validation only. Requires the project's PostgreSQL Docker container.
"""
import argparse
import json
import subprocess
import uuid
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="write the validated catalogue to PostgreSQL")
    parser.add_argument("--container", default="smartmeal-postgres-1")
    parser.add_argument("--file", type=Path, default=ROOT / "data/recipes/starter-recipes.json", help="catalogue JSON file")
    args = parser.parse_args()
    recipes = json.loads(args.file.read_text())
    keys = set()
    for recipe in recipes:
        if recipe["key"] in keys:
            raise ValueError("Duplicate recipe key")
        keys.add(recipe["key"])
        recipe["id"] = str(uuid.uuid5(uuid.NAMESPACE_URL, "https://smartmeal.local/starter-recipes/v1/" + recipe["key"]))
        if recipe["servings"] <= 0 or recipe["preparationTimeMin"] < 0 or not recipe["steps"]:
            raise ValueError("Invalid recipe: " + recipe["key"])
        if not recipe.get("original") and not recipe.get("sourceUrl", "").startswith("https://"):
            raise ValueError("Missing source URL")
        if not recipe["mealTypes"] or not set(recipe["mealTypes"]) <= {"BREAKFAST", "LUNCH", "DINNER", "SNACK"}:
            raise ValueError("Invalid meal types")
        product_names = set()
        for position, ingredient in enumerate(recipe["ingredients"], 1):
            if Decimal(str(ingredient["quantityG"])) <= 0 or ingredient["productName"] in product_names:
                raise ValueError("Invalid or duplicate ingredient")
            product_names.add(ingredient["productName"])
            ingredient["id"] = str(uuid.uuid5(uuid.UUID(recipe["id"]), str(position)))
        if not product_names:
            raise ValueError("Empty ingredient list")
    # SQL literals are quoted separately; no shell interpolation or credentials are used.
    payload = json.dumps(recipes, ensure_ascii=False).replace("'", "''")
    transaction_end = "COMMIT" if args.apply else "ROLLBACK"
    sql = """
BEGIN;
SELECT pg_advisory_xact_lock(hashtext('smartmeal-starter-recipes-v1'));
DO $import$
DECLARE
    item jsonb;
    ingredient jsonb;
    rid uuid;
    pid bigint;
    matches integer;
    position integer;
    instructions text;
    inserted_count integer := 0;
    skipped_count integer := 0;
BEGIN
    FOR item IN SELECT value FROM jsonb_array_elements('__PAYLOAD__'::jsonb)
    LOOP
        rid := (item->>'id')::uuid;
        IF EXISTS (SELECT 1 FROM recipes WHERE id = rid) THEN
            IF NOT EXISTS (SELECT 1 FROM recipes WHERE id = rid AND name = item->>'name') THEN
                RAISE EXCEPTION 'Catalogue ID already belongs to another recipe: %', rid;
            END IF;
            skipped_count := skipped_count + 1;
            CONTINUE;
        END IF;
        IF EXISTS (SELECT 1 FROM recipes WHERE name = item->>'name') THEN
            RAISE EXCEPTION 'Recipe name already exists; resolve manually: %', item->>'name';
        END IF;
        SELECT string_agg(ordinality::text || '. ' || value, E'\n' ORDER BY ordinality)
        INTO instructions FROM jsonb_array_elements_text(item->'steps') WITH ORDINALITY;
        instructions := 'Все веса указаны на весь рецепт: сырая съедобная часть до приготовления; консервы без заливки. '
            || 'Вода и необязательная соль/перец в список покупок не включены.' || E'\n\n' || instructions
            || CASE WHEN coalesce((item->>'original')::boolean, false) THEN E'\n\nСамостоятельный рецепт SmartMeal. Время приготовления ориентировочное.'
               ELSE E'\n\nАдаптация: ' || (item->>'adaptation') || E'\nИсточник: ' || (item->>'sourceTitle') || E'\n' || (item->>'sourceUrl') END;
        INSERT INTO recipes(id, name, description, instructions, preparation_time_min, servings, created_at, updated_at)
        VALUES(rid, item->>'name', coalesce(item->>'description', 'Домашняя адаптация по открытому рецепту; ссылка и изменения указаны в инструкции.'),
               instructions, (item->>'preparationTimeMin')::integer, (item->>'servings')::integer, now(), now());
        position := 0;
        FOR ingredient IN SELECT value FROM jsonb_array_elements(item->'ingredients') LOOP
            SELECT count(*), min(id) INTO matches, pid FROM products WHERE name = ingredient->>'productName';
            IF matches <> 1 THEN
                RAISE EXCEPTION 'Expected one product named %, found %; transaction rolled back', ingredient->>'productName', matches;
            END IF;
            position := position + 1;
            INSERT INTO recipe_ingredients(id, recipe_id, product_id, quantity_g, position)
            VALUES((ingredient->>'id')::uuid, rid, pid, (ingredient->>'quantityG')::numeric, position);
        END LOOP;
        INSERT INTO recipe_meal_types(recipe_id, meal_type)
        SELECT rid, value FROM jsonb_array_elements_text(item->'mealTypes');
        inserted_count := inserted_count + 1;
    END LOOP;
    RAISE NOTICE 'Catalogue: inserted %, skipped %', inserted_count, skipped_count;
END $import$;
__TRANSACTION_END__;
""".replace("__PAYLOAD__", payload).replace("__TRANSACTION_END__", transaction_end)
    result = subprocess.run(["docker", "exec", "-i", args.container, "psql", "-U", "smartmeal", "-d", "smartmeal",
                             "-v", "ON_ERROR_STOP=1"], input=sql, text=True, capture_output=True)
    if result.returncode:
        raise SystemExit(result.stderr)
    print(result.stderr.strip())
    print("Applied." if args.apply else "Validated successfully; transaction rolled back. Use --apply to import.")


if __name__ == "__main__":
    main()
