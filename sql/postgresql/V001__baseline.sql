-- pulse 模块（postgresql）—— 1.0.0 基线。
--
-- PulseKit 客户端上报的统一事件（analytics + apm）。经 msgtrans 长连接 ingest →
-- Redis 队列 → 消费入库。只存元数据，attributes 严禁敏感原文。
--
-- 加新东西请新增 V002、V003…，不要改这个文件（迁移器按 checksum 校验）。

CREATE TABLE pulse_events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    ts BIGINT NOT NULL DEFAULT 0,
    -- Analytics / Crash / Error / Log / Network / Performance / Runtime / Breadcrumb
    kind VARCHAR(24) NOT NULL,
    name VARCHAR(128) NOT NULL DEFAULT '',
    source_module VARCHAR(128),
    attributes TEXT NOT NULL DEFAULT '{}',
    created_at BIGINT
);
CREATE INDEX idx_pulse_events_session ON pulse_events(session_id);
CREATE INDEX idx_pulse_events_kind ON pulse_events(kind, ts);
CREATE UNIQUE INDEX uk_pulse_events_eventid ON pulse_events(event_id);
