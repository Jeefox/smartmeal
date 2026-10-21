ALTER TABLE catalogue_price_refresh_sources ADD COLUMN expected_weight_label varchar(100);
ALTER TABLE catalogue_price_refresh_sources ADD COLUMN conversion_note varchar(300);
CREATE TABLE catalogue_product_discovery (
    product_id bigint PRIMARY KEY REFERENCES products(id),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_attempt_at timestamptz,
    last_error varchar(100),
    lease_token uuid,
    lease_until timestamptz
);
