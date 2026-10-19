CREATE TABLE store_products (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id bigint NOT NULL,
    product_id bigint NOT NULL,
    name varchar(300) NOT NULL,
    package_weight_g integer NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT store_products_store_fk
        FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT store_products_product_fk
        FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT store_products_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT store_products_package_weight_check CHECK (package_weight_g > 0)
);

CREATE INDEX store_products_store_product_idx ON store_products (store_id, product_id);
CREATE INDEX store_products_product_id_idx ON store_products (product_id);

CREATE TABLE price_observations (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_product_id bigint NOT NULL,
    price numeric(12,2) NOT NULL,
    observed_at timestamptz NOT NULL,
    price_source varchar(16) NOT NULL,
    promotional boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT price_observations_store_product_fk
        FOREIGN KEY (store_product_id) REFERENCES store_products (id),
    CONSTRAINT price_observations_price_check CHECK (price > 0),
    CONSTRAINT price_observations_source_check CHECK (price_source IN ('MANUAL', 'RECEIPT'))
);

CREATE INDEX price_observations_latest_idx
    ON price_observations (store_product_id, observed_at DESC, id DESC);
