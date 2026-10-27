CREATE TABLE shopping_sessions (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 source_generation_id uuid NOT NULL REFERENCES meal_plan_generations(id),
 from_date date NOT NULL, to_date date NOT NULL CHECK(to_date>=from_date),
 revision bigint NOT NULL DEFAULT 0, source_snapshot jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(user_id,source_generation_id)
);
CREATE INDEX shopping_sessions_owner_idx ON shopping_sessions(user_id,created_at DESC,id);
CREATE TABLE shopping_session_items (
 id uuid PRIMARY KEY, session_id uuid NOT NULL REFERENCES shopping_sessions(id) ON DELETE CASCADE,
 product_id bigint NOT NULL REFERENCES products(id), position integer NOT NULL CHECK(position>=0),
 snapshot jsonb NOT NULL, required_to_buy_g numeric NOT NULL CHECK(required_to_buy_g>=0),
 picked boolean NOT NULL DEFAULT false, skipped boolean NOT NULL DEFAULT false,
 UNIQUE(session_id,product_id), UNIQUE(session_id,position)
);
CREATE TABLE shopping_session_commands (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 session_id uuid NOT NULL REFERENCES shopping_sessions(id) ON DELETE CASCADE,
 idempotency_key uuid NOT NULL, request_hash varchar(64) NOT NULL,
 result_payload jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(user_id,idempotency_key)
);
CREATE TABLE shopping_purchase_batches (
 id uuid PRIMARY KEY REFERENCES shopping_session_commands(id) ON DELETE CASCADE,
 pantry_operation_id uuid NOT NULL REFERENCES pantry_operations(id)
);
CREATE TABLE shopping_purchase_lines (
 id uuid PRIMARY KEY, batch_id uuid NOT NULL REFERENCES shopping_purchase_batches(id) ON DELETE CASCADE,
 item_id uuid NOT NULL REFERENCES shopping_session_items(id) ON DELETE CASCADE,
 quantity_g numeric NOT NULL CHECK(quantity_g>0), actual_total_rub numeric(12,2) CHECK(actual_total_rub>=0),
 store_product_id bigint REFERENCES store_products(id), received_on date NOT NULL, expires_on date,
 pantry_lot_id uuid NOT NULL UNIQUE REFERENCES pantry_lots(id),
 UNIQUE(batch_id,item_id), CHECK(expires_on IS NULL OR expires_on>=received_on)
);
CREATE INDEX shopping_purchase_lines_item_idx ON shopping_purchase_lines(item_id);
