-- Persist the Mach-O LC_UUID sent in EventSource. A module name can remain unchanged while a
-- re-signer swaps its binary; UUID is the first durable signal needed to distinguish those builds.
ALTER TABLE pulse_events
    ADD COLUMN IF NOT EXISTS source_image_uuid VARCHAR(36);

CREATE INDEX IF NOT EXISTS idx_pulse_events_source_image
    ON pulse_events (project_id, source_module, source_image_uuid, ts);
