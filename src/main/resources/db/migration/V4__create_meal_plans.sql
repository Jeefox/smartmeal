CREATE TABLE meal_plans (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    plan_date date NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    daily_calorie_target_snapshot integer,
    confirmed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT meal_plans_user_fk
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT meal_plans_user_date_unique
        UNIQUE (user_id, plan_date),
    CONSTRAINT meal_plans_status_check
        CHECK (status IN ('DRAFT', 'CONFIRMED')),
    CONSTRAINT meal_plans_calorie_target_check
        CHECK (daily_calorie_target_snapshot > 0),
    CONSTRAINT meal_plans_confirmation_check
        CHECK (
            (status = 'DRAFT'
                AND confirmed_at IS NULL
                AND daily_calorie_target_snapshot IS NULL)
            OR
            (status = 'CONFIRMED'
                AND confirmed_at IS NOT NULL
                AND daily_calorie_target_snapshot IS NOT NULL)
        )
);

CREATE TABLE meal_plan_entries (
    id uuid PRIMARY KEY,
    meal_plan_id uuid NOT NULL,
    recipe_id uuid NOT NULL,
    meal_type varchar(16) NOT NULL,
    portions numeric(6,2) NOT NULL,
    position integer NOT NULL,
    recipe_snapshot jsonb NOT NULL,

    CONSTRAINT meal_plan_entries_plan_fk
        FOREIGN KEY (meal_plan_id) REFERENCES meal_plans (id) ON DELETE CASCADE,
    CONSTRAINT meal_plan_entries_recipe_fk
        FOREIGN KEY (recipe_id) REFERENCES recipes (id),
    CONSTRAINT meal_plan_entries_meal_position_unique
        UNIQUE (meal_plan_id, meal_type, position),
    CONSTRAINT meal_plan_entries_meal_type_check
        CHECK (meal_type IN ('BREAKFAST', 'LUNCH', 'DINNER', 'SNACK')),
    CONSTRAINT meal_plan_entries_portions_check
        CHECK (portions > 0),
    CONSTRAINT meal_plan_entries_position_check
        CHECK (position > 0),
    CONSTRAINT meal_plan_entries_snapshot_check
        CHECK (jsonb_typeof(recipe_snapshot) = 'object')
);

CREATE INDEX meal_plan_entries_recipe_id_idx ON meal_plan_entries (recipe_id);
