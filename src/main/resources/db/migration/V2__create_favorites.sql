CREATE TABLE favorites (
    id         UUID        PRIMARY KEY,
    rom_id     UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT favorites_rom_fk     FOREIGN KEY (rom_id) REFERENCES roms (id) ON DELETE CASCADE,
    CONSTRAINT favorites_rom_unique UNIQUE (rom_id)
);
