CREATE TABLE users (
    id                     UUID        PRIMARY KEY,
    username               TEXT        NOT NULL,
    password_hash          TEXT        NOT NULL,
    role                   VARCHAR(8)  NOT NULL,
    must_change_credentials BOOLEAN    NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT users_role_check    CHECK (role IN ('admin', 'user')),
    CONSTRAINT users_username_key  UNIQUE (username)
);
