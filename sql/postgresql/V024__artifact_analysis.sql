-- Static analysis of the final, signed iOS artifact. One analysis belongs to one managed version;
-- re-uploading replaces the image rows so the console never mixes evidence from two binaries.
CREATE TABLE pulse_artifact_analyses (
    id BIGSERIAL PRIMARY KEY,
    version_id BIGINT NOT NULL REFERENCES pulse_app_versions(id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    archive_sha256 VARCHAR(64) NOT NULL,
    artifact_format VARCHAR(32) NOT NULL,
    image_count INT NOT NULL DEFAULT 0,
    swift_symbol_count INT NOT NULL DEFAULT 0,
    cpp_symbol_count INT NOT NULL DEFAULT 0,
    c_symbol_count INT NOT NULL DEFAULT 0,
    imported_symbol_count INT NOT NULL DEFAULT 0,
    signed_image_count INT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL
);
CREATE UNIQUE INDEX uk_pulse_artifact_analysis_version ON pulse_artifact_analyses(version_id);

CREATE TABLE pulse_artifact_images (
    id BIGSERIAL PRIMARY KEY,
    analysis_id BIGINT NOT NULL REFERENCES pulse_artifact_analyses(id) ON DELETE CASCADE,
    relative_path TEXT NOT NULL,
    architecture VARCHAR(32) NOT NULL,
    image_uuid VARCHAR(64),
    text_sha256 VARCHAR(64) NOT NULL,
    text_size BIGINT NOT NULL,
    has_swift_metadata BOOLEAN NOT NULL DEFAULT FALSE,
    swift_symbol_count INT NOT NULL DEFAULT 0,
    cpp_symbol_count INT NOT NULL DEFAULT 0,
    c_symbol_count INT NOT NULL DEFAULT 0,
    imported_symbol_count INT NOT NULL DEFAULT 0,
    symbols_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    swift_symbols TEXT NOT NULL DEFAULT '[]',
    cpp_symbols TEXT NOT NULL DEFAULT '[]',
    c_symbols TEXT NOT NULL DEFAULT '[]',
    imported_symbols TEXT NOT NULL DEFAULT '[]',
    linked_libraries TEXT NOT NULL DEFAULT '[]',
    signature_status VARCHAR(32) NOT NULL,
    signing_certificates TEXT NOT NULL DEFAULT '[]',
    created_at BIGINT NOT NULL
);
CREATE INDEX idx_pulse_artifact_images_analysis ON pulse_artifact_images(analysis_id);
CREATE INDEX idx_pulse_artifact_images_uuid ON pulse_artifact_images(image_uuid);
