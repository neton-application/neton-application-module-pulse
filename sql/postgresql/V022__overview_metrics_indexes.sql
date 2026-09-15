-- Pulse 总览按 App 和时间范围聚合用户、会话、启动与趋势。
-- 现有 device_id/user_id 索引适合单身份查询，不能高效裁剪某个 App 的时间窗口。

CREATE INDEX idx_pulse_events_project_ts
    ON pulse_events(project_id, ts);

CREATE INDEX idx_pulse_events_project_name_ts
    ON pulse_events(project_id, name, ts);
