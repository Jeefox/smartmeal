ALTER TABLE user_profiles
    ADD COLUMN sex varchar(16),
    ADD CONSTRAINT user_profiles_sex_check
        CHECK (sex IN ('MALE', 'FEMALE'));
