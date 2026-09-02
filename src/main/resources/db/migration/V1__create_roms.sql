CREATE TABLE roms (
    id          UUID        PRIMARY KEY,
    name        TEXT        NOT NULL,
    system      VARCHAR(8)  NOT NULL,
    size_bytes  BIGINT      NOT NULL,
    hash        TEXT        NOT NULL,
    storage_key TEXT        NOT NULL,
    cover_url   TEXT        NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT roms_system_check   CHECK (system IN ('GBA', 'NDS', '3DS')),
    CONSTRAINT roms_size_nonneg    CHECK (size_bytes >= 0),
    CONSTRAINT roms_hash_unique    UNIQUE (hash)
);

CREATE INDEX roms_system_idx     ON roms (system);
CREATE INDEX roms_created_at_idx ON roms (created_at);
