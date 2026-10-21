CREATE TABLE price_entry_batches (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_id uuid NOT NULL,
    store_id bigint NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    payload_hash varchar(64) NOT NULL,
    response_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, request_id)
);
