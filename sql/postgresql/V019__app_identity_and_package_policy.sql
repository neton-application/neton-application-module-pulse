-- Pulse App ID 与包名接入策略。
--
-- App ID 是后台生成并写入 SDK 的稳定公开标识；包名是可变的运行时属性。默认严格匹配，
-- 也可按 App 配置允许名单或完全放开，适应内部重签名/渠道包。现有 App 设为 any，保证先升级
-- 服务端不会拒绝尚未携带 platform/package_name 的老 SDK；运营确认新 SDK 覆盖后可切回 strict。

ALTER TABLE pulse_apps ADD COLUMN package_policy VARCHAR(16) NOT NULL DEFAULT 'strict';
ALTER TABLE pulse_apps ADD COLUMN allowed_packages TEXT;
UPDATE pulse_apps SET package_policy = 'any';
ALTER TABLE pulse_apps ADD CONSTRAINT ck_pulse_apps_package_policy
    CHECK (package_policy IN ('strict', 'allowlist', 'any'));

-- App ID 在整个后台唯一，客户端只需要一个值，不应再依赖 platform 才能解释它。
DROP INDEX uk_pulse_apps_platform_key;
CREATE UNIQUE INDEX uk_pulse_apps_app_key ON pulse_apps(app_key);

-- 把客户端实际运行的平台和包名保留在每条事件上。后台以后能显示“登记包名”和“实际上报包名”的差异。
ALTER TABLE pulse_events ADD COLUMN platform VARCHAR(16);
ALTER TABLE pulse_events ADD COLUMN package_name VARCHAR(191);
CREATE INDEX idx_pulse_events_runtime_package
    ON pulse_events(project_id, platform, package_name, ts);

DROP VIEW pulse_analytics_events;
DROP VIEW pulse_apm_events;
DROP VIEW pulse_security_events;

CREATE VIEW pulse_analytics_events AS
SELECT id, event_id, session_id, ts, kind, name, source_module, attributes, created_at,
       project_id, device_id, installation_id, user_id, app_version, build_number,
       platform, package_name
FROM pulse_events WHERE kind = 'Analytics';

CREATE VIEW pulse_apm_events AS
SELECT id, event_id, session_id, ts, kind, name, source_module, attributes, created_at,
       project_id, device_id, installation_id, user_id, app_version, build_number,
       platform, package_name
FROM pulse_events WHERE kind IN ('Crash', 'Error', 'Log', 'Network', 'Performance', 'Breadcrumb');

CREATE VIEW pulse_security_events AS
SELECT id, event_id, session_id, ts, kind, name, source_module, attributes, created_at,
       project_id, device_id, installation_id, user_id, app_version, build_number,
       platform, package_name
FROM pulse_events WHERE kind = 'Runtime';
