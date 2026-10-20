ALTER TABLE products ADD COLUMN shopping_name text;
ALTER TABLE products ADD COLUMN edible_yield numeric(7,6) NOT NULL DEFAULT 1;

UPDATE products SET shopping_name = name;
ALTER TABLE products ALTER COLUMN shopping_name SET NOT NULL;
ALTER TABLE products ADD CONSTRAINT products_shopping_name_check CHECK (length(btrim(shopping_name)) > 0);
ALTER TABLE products ADD CONSTRAINT products_edible_yield_check CHECK (edible_yield > 0 AND edible_yield <= 1);

-- Existing recipe_snapshot JSON remains unchanged. Application defaults missing
-- shoppingName to productName and missing edibleYield to 1.
