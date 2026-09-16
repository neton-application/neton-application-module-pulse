-- dSYM evidence is attached to a shipped image only when both LC_UUID and architecture match.
-- Keeping symbols in their own table avoids returning tens of thousands of names on every report
-- read while still allowing an exact nearest-address lookup for stripped runtime call sites.
ALTER TABLE pulse_artifact_images
    ADD COLUMN debug_symbol_count INT NOT NULL DEFAULT 0,
    ADD COLUMN debug_symbols_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN debug_symbol_sample TEXT NOT NULL DEFAULT '[]';

CREATE TABLE pulse_artifact_debug_symbols (
    id BIGSERIAL PRIMARY KEY,
    analysis_id BIGINT NOT NULL REFERENCES pulse_artifact_analyses(id) ON DELETE CASCADE,
    image_uuid VARCHAR(64) NOT NULL,
    architecture VARCHAR(32) NOT NULL,
    address BIGINT NOT NULL,
    symbol_name TEXT NOT NULL,
    language VARCHAR(16) NOT NULL
);

CREATE INDEX idx_pulse_artifact_debug_lookup
    ON pulse_artifact_debug_symbols(analysis_id, image_uuid, address);

