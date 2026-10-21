ALTER TABLE catalogue_price_references ADD COLUMN sold_by_weight boolean NOT NULL DEFAULT false;

CREATE TABLE catalogue_price_refresh_sources (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id bigint NOT NULL REFERENCES products(id),
    source_url text NOT NULL,
    expected_sku varchar(40) NOT NULL,
    expected_name varchar(300) NOT NULL,
    package_weight_g integer NOT NULL CHECK (package_weight_g > 0),
    sold_by_weight boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT true,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_attempt_at timestamptz,
    last_success_at timestamptz,
    failures integer NOT NULL DEFAULT 0 CHECK (failures >= 0),
    last_error varchar(100),
    lease_token uuid,
    lease_until timestamptz,
    UNIQUE(product_id, source_url)
);
CREATE INDEX catalogue_price_refresh_due_idx ON catalogue_price_refresh_sources(next_attempt_at) WHERE enabled;
CREATE TABLE catalogue_price_refresh_history (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id bigint NOT NULL REFERENCES catalogue_price_refresh_sources(id),
    checked_at timestamptz NOT NULL,
    price numeric(12,2) NOT NULL CHECK (price > 0)
);
CREATE INDEX catalogue_price_refresh_history_source_idx ON catalogue_price_refresh_history(source_id,checked_at DESC);

-- Product matching is explicit, independent of database IDs; no prices are invented by this migration.
INSERT INTO catalogue_price_refresh_sources(product_id,source_url,expected_sku,expected_name,package_weight_g,sold_by_weight)
SELECT p.id,v.url,v.sku,v.name,v.grams,v.weighted FROM products p JOIN (VALUES
 ('Макароны сухие','https://vkusvill.ru/goods/makarony-spagetti-781/','781','Макароны "Спагетти"',400,false),
 ('Гречневая крупа','https://vkusvill.ru/goods/krupa-grechnevaya-yadritsa-17699/','17699','Крупа гречневая ядрица, 900 г',900,false),
 ('Картофель очищенный','https://vkusvill.ru/goods/kartofel-1713/','1713','Картофель',1000,true),
 ('Морковь','https://vkusvill.ru/goods/morkov-mytaya-18735/','18735','Морковь мытая',1000,true)
) AS v(product_name,url,sku,name,grams,weighted) ON p.name=v.product_name;
