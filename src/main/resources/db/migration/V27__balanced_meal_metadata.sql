CREATE TABLE product_fiber_metadata (
 product_id bigint PRIMARY KEY REFERENCES products(id) ON DELETE CASCADE,
 fiber_per_100g numeric(7,3) CHECK(fiber_per_100g BETWEEN 0 AND 100),
 source varchar(1000), quality varchar(24) NOT NULL CHECK(quality IN ('REFERENCE','LABEL','UNKNOWN')),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(fiber_per_100g IS NULL OR length(trim(source))>0)
);
CREATE TABLE recipe_portion_profiles (
 recipe_id uuid PRIMARY KEY REFERENCES recipes(id) ON DELETE CASCADE,
 role varchar(24) NOT NULL CHECK(role IN ('COMPLETE_MAIN','PROTEIN_MAIN','SIDE','VEGETABLE','SOUP','BREAKFAST')),
 cooked_yield_g numeric(10,2) CHECK(cooked_yield_g>0),
 yield_quality varchar(24) NOT NULL CHECK(yield_quality IN ('MEASURED','AUTHOR_REPORTED','ESTIMATED','UNKNOWN')),
 source varchar(1000), min_g numeric(7,2) NOT NULL CHECK(min_g>0), max_g numeric(7,2) NOT NULL CHECK(max_g>=min_g),
 step_g numeric(7,2) NOT NULL CHECK(step_g>0), contains_starch boolean NOT NULL DEFAULT false,
 contains_vegetables boolean NOT NULL DEFAULT false,
 updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK((yield_quality='UNKNOWN' AND cooked_yield_g IS NULL) OR (yield_quality<>'UNKNOWN' AND cooked_yield_g IS NOT NULL AND length(trim(source))>0))
);
CREATE TABLE participant_nutrient_targets (
 user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE, participant_id uuid NOT NULL,
 adult_confirmed boolean NOT NULL DEFAULT false, weight_kg numeric(6,2) CHECK(weight_kg BETWEEN 20 AND 400),
 protein_target_g numeric(7,2) CHECK(protein_target_g BETWEEN 0 AND 400),
 fiber_target_g numeric(7,2) CHECK(fiber_target_g BETWEEN 0 AND 100),
 max_meal_g numeric(7,2) NOT NULL DEFAULT 650 CHECK(max_meal_g BETWEEN 250 AND 1200),
 target_source varchar(24) NOT NULL CHECK(target_source IN ('REFERENCE','USER_SET','UNSET')),
 revision bigint NOT NULL DEFAULT 0, updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(user_id,participant_id),
 CHECK(adult_confirmed OR (protein_target_g IS NULL AND fiber_target_g IS NULL))
);
CREATE TABLE portion_feedback (
 user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 meal_plan_entry_id uuid NOT NULL REFERENCES meal_plan_entries(id) ON DELETE CASCADE,
 participant_id uuid NOT NULL, rating varchar(24) NOT NULL CHECK(rating IN ('TOO_LITTLE','ENOUGH','TOO_MUCH')),
 revision bigint NOT NULL DEFAULT 1, updated_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(meal_plan_entry_id,participant_id)
);
