CREATE TABLE recipe_images (
    id uuid PRIMARY KEY,
    recipe_id uuid NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
    object_key varchar(500) NOT NULL UNIQUE,
    content_type varchar(100) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes > 0),
    width integer NOT NULL CHECK (width > 0),
    height integer NOT NULL CHECK (height > 0),
    alt_text varchar(300),
    position integer NOT NULL CHECK (position > 0),
    created_at timestamptz NOT NULL,
    UNIQUE (recipe_id, position)
);

CREATE INDEX recipe_images_recipe_id_idx ON recipe_images(recipe_id);
