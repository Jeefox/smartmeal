INSERT INTO equipment(code,name) VALUES ('AIR_FRYER','Аэрогриль') ON CONFLICT(code) DO NOTHING;
CREATE TABLE user_kitchen_setup (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    configured_at timestamptz NOT NULL DEFAULT now()
);
-- Existing explicit selections need no repeated first-run prompt.
INSERT INTO user_kitchen_setup(user_id) SELECT DISTINCT user_id FROM user_equipment;
