ALTER TABLE recipes ADD COLUMN portion_step numeric(4,2) NOT NULL DEFAULT 1 CHECK (portion_step > 0 AND portion_step <= 2);
ALTER TABLE recipes ADD COLUMN min_portions numeric(4,2) NOT NULL DEFAULT 1 CHECK (min_portions > 0);
ALTER TABLE recipes ADD COLUMN max_portions numeric(4,2) NOT NULL DEFAULT 2 CHECK (max_portions >= min_portions AND max_portions <= 4);
ALTER TABLE recipes ADD COLUMN dish_family varchar(100);
-- Unknown allergen data must not be treated as verified absence of allergens.
ALTER TABLE products ADD COLUMN allergen_data_complete boolean NOT NULL DEFAULT false;
ALTER TABLE products ADD COLUMN allergen_verification_note varchar(500);
CREATE TABLE meal_plan_generations (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    document jsonb NOT NULL,
    commit_key uuid,
    saved_plan_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    UNIQUE(user_id, commit_key),
    CHECK(expires_at > created_at)
);
CREATE INDEX meal_plan_generations_owner_idx ON meal_plan_generations(user_id, created_at DESC);
CREATE INDEX price_observations_latest_regular_idx ON price_observations(store_product_id, observed_at DESC, id DESC)
    WHERE NOT promotional AND invalidated_at IS NULL;
