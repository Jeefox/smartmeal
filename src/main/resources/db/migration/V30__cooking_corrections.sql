CREATE TABLE cooking_corrections (
 id uuid PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES users(id),
 operation_id uuid NOT NULL REFERENCES pantry_operations(id),
 batch_id uuid NOT NULL REFERENCES cooking_batches(id),
 entry_id uuid REFERENCES meal_plan_entries(id),
 kind varchar(32) NOT NULL CHECK (kind IN ('YIELD','CONSUMPTION_REVERSAL')),
 reason varchar(300) NOT NULL CHECK (length(trim(reason)) > 0),
 before_payload jsonb NOT NULL,
 after_payload jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX cooking_corrections_history_idx ON cooking_corrections(user_id,batch_id,created_at DESC,id);
