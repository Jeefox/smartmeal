-- New discovery uses the official public Chizhik catalogue. Existing Vkusvill
-- references remain as a fallback until a verified Chizhik match is published.
UPDATE catalogue_price_refresh_sources
SET enabled = false,
    lease_token = NULL,
    lease_until = NULL
WHERE source_url LIKE 'https://vkusvill.ru/%';

INSERT INTO catalogue_product_discovery(product_id, next_attempt_at)
SELECT DISTINCT product_id, now()
FROM recipe_ingredients
ON CONFLICT(product_id) DO UPDATE
SET next_attempt_at = now(),
    last_error = NULL,
    lease_token = NULL,
    lease_until = NULL;
