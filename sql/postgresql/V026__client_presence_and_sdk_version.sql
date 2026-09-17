-- Live msgtrans connections are ephemeral in Redis; this table keeps the auditable lifecycle and
-- SDK adoption history. Event rows also carry the SDK release so historical event queries do not
-- depend on a connection record that may be retained for a shorter period.
ALTER TABLE pulse_events ADD COLUMN sdk_version VARCHAR(64);
CREATE INDEX idx_pulse_events_sdk_version ON pulse_events(project_id, sdk_version, ts);

CREATE TABLE pulse_client_connections (
    id BIGSERIAL PRIMARY KEY,
    connection_id VARCHAR(64) NOT NULL UNIQUE,
    project_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(128) NOT NULL,
    installation_id VARCHAR(128) NOT NULL,
    platform VARCHAR(32) NOT NULL,
    device_type VARCHAR(64) NOT NULL,
    package_name VARCHAR(255),
    app_version VARCHAR(64),
    build_number BIGINT NOT NULL DEFAULT 0,
    sdk_version VARCHAR(64) NOT NULL,
    capabilities TEXT NOT NULL DEFAULT '[]',
    connected_at BIGINT NOT NULL,
    last_heartbeat_at BIGINT NOT NULL,
    disconnected_at BIGINT,
    disconnect_reason VARCHAR(128)
);

CREATE INDEX idx_pulse_connections_app_online
    ON pulse_client_connections(project_id, disconnected_at, last_heartbeat_at DESC);
CREATE INDEX idx_pulse_connections_sdk
    ON pulse_client_connections(project_id, sdk_version, connected_at DESC);
