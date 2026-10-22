-- V1 has already been applied. Repair the table in place so existing UUIDs and
-- employee numbers remain unchanged. PostgreSQL runs this Flyway migration in
-- one transaction; the lock keeps the checks and sequence adjustment consistent.
LOCK TABLE app_user IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM app_user
        WHERE user_number < 1 OR user_number = 9223372036854775807
    ) THEN
        RAISE EXCEPTION 'app_user.user_number must be between 1 and 9223372036854775806 before V2';
    END IF;

    IF EXISTS (
        SELECT 1 FROM app_user
        GROUP BY user_number HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Duplicate app_user.user_number values must be resolved before V2';
    END IF;

    IF EXISTS (
        SELECT 1 FROM app_user
        WHERE btrim(email_normalized, ' ' || chr(9) || chr(10) || chr(11) || chr(12) || chr(13)) = ''
    ) THEN
        RAISE EXCEPTION 'Blank app_user.email_normalized values must be resolved before V2';
    END IF;

    IF EXISTS (
        SELECT 1 FROM app_user
        GROUP BY lower(btrim(email_normalized, ' ' || chr(9) || chr(10) || chr(11) || chr(12) || chr(13)))
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Duplicate normalized app_user emails must be resolved before V2';
    END IF;
END
$$;

-- Trim ASCII whitespace (space, TAB, LF, VT, FF, CR) at the boundaries and
-- lowercase existing identifiers before making their uniqueness permanent.
UPDATE app_user
SET email_normalized = lower(btrim(email_normalized, ' ' || chr(9) || chr(10) || chr(11) || chr(12) || chr(13)))
WHERE email_normalized <>
      lower(btrim(email_normalized, ' ' || chr(9) || chr(10) || chr(11) || chr(12) || chr(13)));

ALTER TABLE app_user
    ADD CONSTRAINT uq_app_user_user_number UNIQUE (user_number),
    ADD CONSTRAINT uq_app_user_email_normalized UNIQUE (email_normalized),
    ADD CONSTRAINT ck_app_user_user_number_positive CHECK (user_number > 0),
    ADD CONSTRAINT ck_app_user_email_normalized CHECK (
        email_normalized = lower(btrim(email_normalized, ' ' || chr(9) || chr(10) || chr(11) || chr(12) || chr(13)))
        AND email_normalized <> ''
    ),
    ADD CONSTRAINT ck_app_user_role CHECK (role IN ('OWNER', 'OFFICE', 'TECHNICIAN')),
    ADD CONSTRAINT ck_app_user_version_nonnegative CHECK (version >= 0);

ALTER TABLE app_user
    ALTER COLUMN user_number ADD GENERATED ALWAYS AS IDENTITY,
    ALTER COLUMN active SET DEFAULT true,
    ALTER COLUMN version SET DEFAULT 0;

-- V1 used a timestamp without zone. Interpret historical values as UTC;
-- this assumption must be checked if real rows were inserted in local time.
ALTER TABLE app_user
    ALTER COLUMN created_at TYPE timestamptz(6)
        USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN created_at SET DEFAULT now();

-- Identity sequences do not inspect pre-existing values. Advance past the
-- highest preserved employee number; on an empty table, the first value is 1.
SELECT setval(
    pg_get_serial_sequence('app_user', 'user_number'),
    COALESCE((SELECT max(user_number) FROM app_user), 1),
    EXISTS (SELECT 1 FROM app_user)
);
