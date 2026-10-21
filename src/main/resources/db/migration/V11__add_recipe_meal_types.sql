CREATE TABLE recipe_meal_types (
    recipe_id uuid NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
    meal_type varchar(16) NOT NULL CHECK (meal_type IN ('BREAKFAST', 'LUNCH', 'DINNER', 'SNACK')),
    PRIMARY KEY (recipe_id, meal_type)
);

CREATE INDEX recipe_meal_types_type_recipe_idx ON recipe_meal_types (meal_type, recipe_id);

-- Existing recipes stay unclassified. Do not guess meal suitability from names.
