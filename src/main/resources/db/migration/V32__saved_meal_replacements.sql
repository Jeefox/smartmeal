CREATE TABLE meal_entry_replacements (
 id uuid PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES users(id),
 entry_id uuid NOT NULL REFERENCES meal_plan_entries(id) ON DELETE CASCADE,
 operation_id uuid NOT NULL UNIQUE REFERENCES pantry_operations(id),
 before_recipe_id uuid NOT NULL REFERENCES recipes(id),
 before_snapshot jsonb NOT NULL,
 before_portions numeric(6,2) NOT NULL CHECK(before_portions>0),
 after_recipe_id uuid NOT NULL REFERENCES recipes(id),
 after_snapshot jsonb NOT NULL,
 after_portions numeric(6,2) NOT NULL CHECK(after_portions>0),
 before_basket jsonb NOT NULL,
 after_basket jsonb NOT NULL,
 applied_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 undone_at timestamptz,
 undo_operation_id uuid UNIQUE REFERENCES pantry_operations(id),
 CHECK((undone_at IS NULL)=(undo_operation_id IS NULL))
);
CREATE INDEX meal_entry_replacements_history_idx ON meal_entry_replacements(user_id,entry_id,applied_at DESC,id);
ALTER TABLE shopping_sessions ADD COLUMN menu_reconciled_at timestamptz;
