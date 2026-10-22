CREATE TABLE pantry_accounts (
 user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE, revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0)
);
CREATE TABLE pantry_lots (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id),
 product_id bigint NOT NULL REFERENCES products(id), quantity_on_hand_g numeric(50,30) NOT NULL CHECK(quantity_on_hand_g>=0),
 received_on date NOT NULL, expires_on date,
 source varchar(16) NOT NULL CHECK(source IN ('INITIAL','PURCHASE','MANUAL')),
 store_product_id bigint REFERENCES store_products(id),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 UNIQUE(id,user_id)
);
CREATE INDEX pantry_lots_owner_product ON pantry_lots(user_id,product_id,expires_on);
CREATE TABLE pantry_operations (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id), idempotency_key uuid NOT NULL,
 request_hash varchar(64) NOT NULL, kind varchar(64) NOT NULL,
 request_payload jsonb NOT NULL, result_payload jsonb NOT NULL, created_at timestamptz NOT NULL,
 UNIQUE(user_id,idempotency_key), UNIQUE(id,user_id)
);
CREATE TABLE pantry_movements (
 id uuid PRIMARY KEY, user_id uuid NOT NULL, operation_id uuid NOT NULL, lot_id uuid NOT NULL,
 quantity_delta_g numeric(50,30) NOT NULL CHECK(quantity_delta_g<>0), reason varchar(300) NOT NULL,
 meal_plan_entry_id uuid REFERENCES meal_plan_entries(id), reversal_of_id uuid UNIQUE REFERENCES pantry_movements(id),
 created_at timestamptz NOT NULL,
 FOREIGN KEY(operation_id,user_id) REFERENCES pantry_operations(id,user_id),
 FOREIGN KEY(lot_id,user_id) REFERENCES pantry_lots(id,user_id)
);
CREATE INDEX pantry_movements_history ON pantry_movements(user_id,created_at,id);
CREATE TABLE pantry_reservations (
 user_id uuid NOT NULL, lot_id uuid NOT NULL, meal_plan_entry_id uuid NOT NULL REFERENCES meal_plan_entries(id) ON DELETE CASCADE,
 quantity_g numeric(50,30) NOT NULL CHECK(quantity_g>0),
 PRIMARY KEY(lot_id,meal_plan_entry_id), FOREIGN KEY(lot_id,user_id) REFERENCES pantry_lots(id,user_id)
);
CREATE INDEX pantry_reservations_owner ON pantry_reservations(user_id,meal_plan_entry_id);
CREATE TABLE meal_entry_inventory_states (
 meal_plan_entry_id uuid PRIMARY KEY REFERENCES meal_plan_entries(id) ON DELETE CASCADE,
 state varchar(16) NOT NULL CHECK(state IN ('OPEN','DEDUCTED','SKIPPED')),
 deduction_operation_id uuid REFERENCES pantry_operations(id), updated_at timestamptz NOT NULL
);
ALTER TABLE meal_plans ADD COLUMN cancelled_at timestamptz;
ALTER TABLE meal_plans ADD COLUMN cancellation_history jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE meal_plans DROP CONSTRAINT meal_plans_status_check;
ALTER TABLE meal_plans ADD CONSTRAINT meal_plans_status_check CHECK(status IN ('DRAFT','CONFIRMED','CANCELLED'));
ALTER TABLE meal_plans DROP CONSTRAINT meal_plans_confirmation_check;
ALTER TABLE meal_plans ADD CONSTRAINT meal_plans_confirmation_check CHECK(
 (status='DRAFT' AND confirmed_at IS NULL AND daily_calorie_target_snapshot IS NULL AND cancelled_at IS NULL)
 OR (status='CONFIRMED' AND confirmed_at IS NOT NULL AND daily_calorie_target_snapshot IS NOT NULL AND cancelled_at IS NULL)
 OR (status='CANCELLED' AND cancelled_at IS NOT NULL)
);
