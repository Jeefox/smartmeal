CREATE TABLE catalogue_price_references (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id bigint NOT NULL REFERENCES products(id),
    name varchar(300) NOT NULL CHECK (length(btrim(name)) > 0),
    package_weight_g integer NOT NULL CHECK (package_weight_g > 0),
    price numeric(12,2) NOT NULL CHECK (price > 0),
    source_name varchar(200) NOT NULL,
    source_url text NOT NULL,
    source_scope varchar(300) NOT NULL,
    retrieved_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    active boolean NOT NULL DEFAULT true,
    CHECK (expires_at > retrieved_at),
    UNIQUE (product_id, source_url)
);
CREATE INDEX catalogue_price_references_product_idx ON catalogue_price_references(product_id) WHERE active;
