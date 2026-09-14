-- pulse 模块（postgresql）—— 事件携带构建标识（V012）。
--
-- 版本是「这次运行」的属性,不是单个事件的属性,所以随 identity 按批次上报、在这里展开到行上。
-- 加这两列的直接原因是崩溃堆栈:符号表按构建存,一条不知道自己属于哪个构建的崩溃无法符号化。
-- 顺带也让「某个版本的崩溃率」「新版本引入的问题」这类问题能直接按列聚合。
--
-- 可空:老客户端不发这两个字段,不能因此拒绝它的数据。

ALTER TABLE pulse_events ADD COLUMN app_version VARCHAR(64);
ALTER TABLE pulse_events ADD COLUMN build_number BIGINT;

CREATE INDEX idx_pulse_events_build ON pulse_events(project_id, build_number, ts);

-- 三个业务域视图重建以带上新列（CREATE OR REPLACE VIEW 不允许改变列集合）。
DROP VIEW pulse_analytics_events;
DROP VIEW pulse_apm_events;
DROP VIEW pulse_security_events;

CREATE VIEW pulse_analytics_events AS
SELECT id, event_id, session_id, ts, kind, name, source_module, attributes, created_at,
       project_id, device_id, installation_id, user_id, app_version, build_number
FROM pulse_events WHERE kind = 'Analytics';

CREATE VIEW pulse_apm_events AS
SELECT id, event_id, session_id, ts, kind, name, source_module, attributes, created_at,
       project_id, device_id, installation_id, user_id, app_version, build_number
FROM pulse_events WHERE kind IN ('Crash', 'Error', 'Log', 'Network', 'Performance', 'Breadcrumb');

CREATE VIEW pulse_security_events AS
SELECT id, event_id, session_id, ts, kind, name, source_module, attributes, created_at,
       project_id, device_id, installation_id, user_id, app_version, build_number
FROM pulse_events WHERE kind = 'Runtime';
