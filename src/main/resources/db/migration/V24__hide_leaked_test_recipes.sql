ALTER TABLE recipes ADD COLUMN catalogue_visible boolean NOT NULL DEFAULT true;
-- Only the exact fixture signature is quarantined. Saved recipe snapshots remain intact.
UPDATE recipes r SET catalogue_visible=false
WHERE r.name ~ '^Save test [0-9a-f-]{36}$' AND r.instructions='Приготовить'
AND EXISTS (SELECT 1 FROM recipe_ingredients i JOIN products p ON p.id=i.product_id
 JOIN product_categories c ON c.id=p.category_id
 WHERE i.recipe_id=r.id AND c.code ~ '^SAVE_[0-9a-f-]{36}$' AND p.name='Тестовый продукт')
AND NOT EXISTS (SELECT 1 FROM recipe_ingredients i JOIN products p ON p.id=i.product_id
 JOIN product_categories c ON c.id=p.category_id WHERE i.recipe_id=r.id AND c.code !~ '^SAVE_[0-9a-f-]{36}$');
