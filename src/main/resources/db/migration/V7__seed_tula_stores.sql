-- Stores explicitly selected by the user; addresses checked against the linked map cards.
-- DIXY: https://yandex.ru/maps/-/CXFgjAZ8
-- CHIZHIK (Krasnoarmeysky): https://yandex.ru/maps/-/CXFgj4p1
-- PYATEROCHKA: https://yandex.ru/maps/-/CXFgjC0W
-- CHIZHIK (Frunze): https://yandex.ru/maps/-/CXFgnEk6
-- MAGNIT (Frunze): https://yandex.ru/maps/-/CXFgnDMA
-- MAGNIT EXTRA (Soyfera): https://yandex.ru/maps/-/CXFgn-Y4
-- SVETOFOR: https://yandex.ru/maps/-/CXFgrLkk
INSERT INTO stores (chain_id, city, address)
SELECT chain.id, 'Тула', seed.address
FROM (VALUES
    ('DIXY', 'Красноармейский проспект, 25'),
    ('CHIZHIK', 'Красноармейский проспект, 25'),
    ('PYATEROCHKA', 'улица Фрунзе, 8'),
    ('CHIZHIK', 'улица Фрунзе, 7А'),
    ('MAGNIT', 'улица Фрунзе, 7Б'),
    ('MAGNIT', 'улица Сойфера, 18А'),
    ('SVETOFOR', 'улица Макаренко, 19')
) AS seed(chain_code, address)
JOIN retail_chains AS chain ON chain.code = seed.chain_code
WHERE NOT EXISTS (
    SELECT 1
    FROM stores AS existing
    WHERE existing.chain_id = chain.id
        AND existing.city = 'Тула'
        AND existing.address = seed.address
);
