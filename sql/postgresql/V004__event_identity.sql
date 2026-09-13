-- pulse 模块（postgresql）—— 事件携带身份（V004）。
--
-- V001 的 pulse_events 只有 session_id。客户端持久化的 device_id / installation_id 从未上到
-- 服务端,导致:
--   * DAU/MAU 实际统计的是会话数,而不是设备/用户数;
--   * crash-per-device 这类质量指标根本算不出来;
--   * identify() 设置的 user_id 无处落地,跨能力的用户维度关联断掉。
-- 客户端一侧的持久化只有配上这几列才有意义。
--
-- 批次级身份:同一批事件共享一份 identity(客户端按 batch 发一次),这里展开到行上,
-- 便于直接按列聚合,不必解析 attributes。
--
-- 全部可空:老客户端(只发裸事件数组)仍然可以上报,这些列留空,不阻断升级。

ALTER TABLE pulse_events ADD COLUMN project_id VARCHAR(64);
ALTER TABLE pulse_events ADD COLUMN device_id VARCHAR(64);
ALTER TABLE pulse_events ADD COLUMN installation_id VARCHAR(64);
ALTER TABLE pulse_events ADD COLUMN user_id VARCHAR(64);

-- 设备维度与用户维度的聚合各来一条:两者的基数与查询模式不同,合成一个复合索引反而两头不讨好。
CREATE INDEX idx_pulse_events_device ON pulse_events(device_id, ts);
CREATE INDEX idx_pulse_events_user ON pulse_events(user_id, ts);

-- 三个业务域视图必须重建才能带上新列(CREATE OR REPLACE VIEW 不允许改变列集合)。
-- 归类规则不变,见 V003 的说明。
DROP VIEW analytics_events;
DROP VIEW apm_events;
DROP VIEW security_events;

CREATE VIEW analytics_events AS
SELECT id, event_id, session_id, ts, kind, name, source_module, attributes, created_at,
       project_id, device_id, installation_id, user_id
FROM pulse_events
WHERE kind = 'Analytics';

CREATE VIEW apm_events AS
SELECT id, event_id, session_id, ts, kind, name, source_module, attributes, created_at,
       project_id, device_id, installation_id, user_id
FROM pulse_events
WHERE kind IN ('Crash', 'Error', 'Log', 'Network', 'Performance', 'Breadcrumb');

CREATE VIEW security_events AS
SELECT id, event_id, session_id, ts, kind, name, source_module, attributes, created_at,
       project_id, device_id, installation_id, user_id
FROM pulse_events
WHERE kind = 'Runtime';
