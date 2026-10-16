CREATE TYPE "activity_level" AS ENUM (
    'SEDENTARY',
    'LOW',
    'MODERATE',
'HIGH',
'VERY_HIGH'
);

CREATE TYPE "goal" AS ENUM (
    'LOSE_WEIGHT',
    'MAINTAIN_WEIGHT',
    'GAIN_WEIGHT'
);

CREATE TABLE "users" (
    "id" uuid PRIMARY KEY,
    "email" varchar UNIQUE NOT NULL,
"password_hash" varchar NOT NULL,
"created_at" timestamptz NOT NULL DEFAULT now(),
"updated_at" timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE "user_profiles" (
    "user_id" uuid PRIMARY KEY,
    "age" smallint CHECK ("age" > 0),
"height_cm" integer CHECK ("height_cm" > 0),
"weight_kg" numeric(5,2) CHECK ("weight_kg" > 0),
"activity_level" activity_level,
"goal" goal,
"target_weight_kg" numeric(5,2) CHECK ("target_weight_kg" > 0),
"daily_calorie_target" integer CHECK ("daily_calorie_target" > 0)
);

CREATE TABLE "products" (
    "id" bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
"category_id" bigint NOT NULL,
"name" varchar NOT NULL,
"description" varchar(300),
"calories_per_100g" int NOT NULL CHECK ("calories_per_100g" >= 0),
"protein_per_100g" numeric(7,2) NOT NULL CHECK ("protein_per_100g" >= 0),
"fat_per_100g" numeric(7,2) NOT NULL CHECK ("fat_per_100g" >= 0),
"carbs_per_100g" numeric(7,2) NOT NULL CHECK ("carbs_per_100g" >= 0),
"created_at" timestamptz NOT NULL DEFAULT now(),
"updated_at" timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE "product_categories" (
"id" bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
"code" varchar UNIQUE NOT NULL,
"name" varchar NOT NULL
);

CREATE TABLE "allergens" (
    "id" bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
"code" varchar UNIQUE NOT NULL,
"name" varchar NOT NULL
);

CREATE TABLE "product_allergens" (
"product_id" bigint NOT NULL,
"allergen_id" bigint NOT NULL,
PRIMARY KEY ("product_id", "allergen_id")
);

CREATE TABLE "user_allergies" (
    "user_id" uuid NOT NULL,
    "allergen_id" bigint NOT NULL,
PRIMARY KEY ("user_id", "allergen_id")
);

CREATE TABLE "user_excluded_products" (
    "user_id" uuid NOT NULL,
"product_id" bigint NOT NULL,
PRIMARY KEY ("user_id", "product_id")
);

CREATE TABLE "equipment" (
    "id" bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
"code" varchar UNIQUE NOT NULL,
"name" varchar NOT NULL
);

CREATE TABLE "user_equipment" (
    "user_id" uuid NOT NULL,
    "equipment_id" bigint NOT NULL,
PRIMARY KEY ("user_id", "equipment_id")
);

CREATE TABLE "recipes" (
    "id" uuid PRIMARY KEY,
"name" varchar NOT NULL,
"description" varchar,
"instructions" text NOT NULL,
"preparation_time_min" int NOT NULL CHECK ("preparation_time_min" >= 0),
"servings" integer NOT NULL CHECK ("servings" > 0),
"created_at" timestamptz NOT NULL DEFAULT now(),
"updated_at" timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE "recipe_ingredients" (
"id" uuid PRIMARY KEY,
"recipe_id" uuid NOT NULL,
"product_id" bigint NOT NULL,
"quantity_g" numeric(10,2) NOT NULL CHECK ("quantity_g" > 0),
"position" int NOT NULL  CHECK ("position" > 0)
);

CREATE TABLE "recipe_equipment" (
    "recipe_id" uuid NOT NULL,
"equipment_id" bigint NOT NULL,
PRIMARY KEY ("recipe_id", "equipment_id")
);

CREATE UNIQUE INDEX ON "recipe_ingredients" ("recipe_id", "product_id");

CREATE UNIQUE INDEX ON "recipe_ingredients" ("recipe_id", "position");

ALTER TABLE "user_profiles" ADD FOREIGN KEY ("user_id") REFERENCES "users" ("id") ON DELETE CASCADE DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "product_allergens" ADD FOREIGN KEY ("product_id") REFERENCES "products" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "product_allergens" ADD FOREIGN KEY ("allergen_id") REFERENCES "allergens" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "user_allergies" ADD FOREIGN KEY ("user_id") REFERENCES "users" ("id") ON DELETE CASCADE DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "products" ADD FOREIGN KEY ("category_id") REFERENCES "product_categories" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "user_excluded_products" ADD FOREIGN KEY ("product_id") REFERENCES "products" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "user_excluded_products" ADD FOREIGN KEY ("user_id") REFERENCES "users" ("id") ON DELETE CASCADE DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "user_allergies" ADD FOREIGN KEY ("allergen_id") REFERENCES "allergens" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "user_equipment" ADD FOREIGN KEY ("equipment_id") REFERENCES "equipment" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "user_equipment" ADD FOREIGN KEY ("user_id") REFERENCES "users" ("id") ON DELETE CASCADE DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "recipe_ingredients" ADD FOREIGN KEY ("recipe_id") REFERENCES "recipes" ("id") ON DELETE CASCADE DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "recipe_ingredients" ADD FOREIGN KEY ("product_id") REFERENCES "products" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "recipe_equipment" ADD FOREIGN KEY ("recipe_id") REFERENCES "recipes" ("id") ON DELETE CASCADE DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "recipe_equipment" ADD FOREIGN KEY ("equipment_id") REFERENCES "equipment" ("id") DEFERRABLE INITIALLY IMMEDIATE;