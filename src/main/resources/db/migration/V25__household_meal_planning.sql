CREATE TABLE household_members (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 name varchar(80) NOT NULL CHECK(length(trim(name))>0),
 daily_calorie_target integer NOT NULL CHECK(daily_calorie_target BETWEEN 500 AND 10000),
 active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX household_members_owner_idx ON household_members(user_id,active);
CREATE TABLE household_member_allergies (
 member_id uuid NOT NULL REFERENCES household_members(id) ON DELETE CASCADE,
 allergen_id bigint NOT NULL REFERENCES allergens(id), PRIMARY KEY(member_id,allergen_id)
);
CREATE TABLE household_member_exclusions (
 member_id uuid NOT NULL REFERENCES household_members(id) ON DELETE CASCADE,
 product_id bigint NOT NULL REFERENCES products(id), PRIMARY KEY(member_id,product_id)
);
CREATE TABLE meal_plan_households (
 meal_plan_id uuid PRIMARY KEY REFERENCES meal_plans(id) ON DELETE CASCADE,
 participants jsonb NOT NULL CHECK(jsonb_typeof(participants)='array')
);
