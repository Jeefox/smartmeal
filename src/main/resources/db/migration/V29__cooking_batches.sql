CREATE TABLE cooking_batches (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id),
 source_snapshot jsonb NOT NULL, source_fingerprint varchar(64) NOT NULL,
 name varchar NOT NULL, planned_multiplier numeric(50,30) NOT NULL CHECK(planned_multiplier>0),
 estimated_yield_g numeric(50,30) NOT NULL CHECK(estimated_yield_g>0),
 actual_yield_g numeric(50,30) CHECK(actual_yield_g>0), cook_on date NOT NULL, use_by date NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN ('PLANNED','COOKED','DISCARDED','CANCELLED')),
 cooked_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(), CHECK(use_by>=cook_on)
);
CREATE INDEX cooking_batches_owner_idx ON cooking_batches(user_id,cook_on);
CREATE TABLE cooking_allocations (
 id uuid PRIMARY KEY, batch_id uuid NOT NULL REFERENCES cooking_batches(id),
 entry_id uuid NOT NULL REFERENCES meal_plan_entries(id), component_id varchar(120) NOT NULL,
 multiplier numeric(50,30) NOT NULL CHECK(multiplier>0), raw_demands jsonb NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN ('ACTIVE','CONSUMED','RELEASED')),
 consumed_at timestamptz, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX cooking_one_active_component_idx ON cooking_allocations(entry_id,component_id) WHERE status IN ('ACTIVE','CONSUMED');
CREATE INDEX cooking_allocations_batch_idx ON cooking_allocations(batch_id);
