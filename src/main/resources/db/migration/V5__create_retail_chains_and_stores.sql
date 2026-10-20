CREATE TABLE retail_chains (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(64) NOT NULL,
    name varchar(150) NOT NULL,

    CONSTRAINT retail_chains_code_unique UNIQUE (code),
    CONSTRAINT retail_chains_code_not_blank CHECK (btrim(code) <> ''),
    CONSTRAINT retail_chains_name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE stores (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    chain_id bigint NOT NULL,
    city varchar(120) NOT NULL,
    address varchar(300) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT stores_chain_fk
        FOREIGN KEY (chain_id) REFERENCES retail_chains (id),
    CONSTRAINT stores_city_not_blank CHECK (btrim(city) <> ''),
    CONSTRAINT stores_address_not_blank CHECK (btrim(address) <> '')
);

CREATE INDEX stores_chain_id_idx ON stores (chain_id);
CREATE INDEX stores_city_idx ON stores (city);
