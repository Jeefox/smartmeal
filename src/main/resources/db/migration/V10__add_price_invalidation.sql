ALTER TABLE price_observations
    ADD COLUMN invalidated_at timestamptz,
    ADD COLUMN invalidation_reason varchar(300),
    ADD COLUMN invalidated_by text;

ALTER TABLE price_observations ADD CONSTRAINT price_observations_invalidation_check CHECK (
    (invalidated_at IS NULL AND invalidation_reason IS NULL AND invalidated_by IS NULL)
    OR (invalidated_at IS NOT NULL AND invalidation_reason IS NOT NULL AND invalidated_by IS NOT NULL
        AND length(btrim(invalidation_reason)) > 0 AND length(btrim(invalidated_by)) > 0)
);

CREATE INDEX price_observations_valid_latest_idx
    ON price_observations (store_product_id, observed_at DESC, id DESC)
    WHERE invalidated_at IS NULL;
