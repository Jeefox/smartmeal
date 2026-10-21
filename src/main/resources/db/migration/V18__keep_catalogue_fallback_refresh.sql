-- Keep legacy catalogue refresh available when the preferred source is blocked.
-- Unsafe product mappings disabled by verification must stay disabled.
UPDATE catalogue_price_refresh_sources
SET enabled = true
WHERE source_url LIKE 'https://vkusvill.ru/%'
  AND last_error IS DISTINCT FROM 'UNSAFE_PRODUCT_MATCH';
