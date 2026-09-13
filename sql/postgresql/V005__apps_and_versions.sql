-- pulse 模块（postgresql）—— 应用管理与版本管理，并统一表/视图前缀（V005）。
--
-- 1) 前缀统一。V003/V004 建的三个业务域视图叫 analytics_events / apm_events / security_events,
--    没有模块前缀,在共库里容易和别的模块撞名。统一改成 pulse_ 前缀,与 pulse_events 一致。
--    视图不能改名后再改列集合,所以直接 DROP 再按新名字重建(视图无数据,重建零代价)。
--
-- 2) 应用管理。一个平台一条 App 记录:同一产品的 iOS 与 Android 各自独立管理、各自维护版本,
--    因为两端的包名、审核节奏、强更策略本来就不同,合成一条只会逼出一堆平台判断。
--    Web/鸿蒙同理。Web 没有包名,用 app_key 标识。
--
-- 3) 版本管理与启动更新检查。客户端启动时带 (platform, package_name, build_number) 来问,
--    服务端在该 App 已发布的版本里找 build_number 更大的最新一条,返回是否更新、更新说明、
--    下载地址、是否强制。强制更新意味着客户端不允许继续走业务逻辑。
--
--    用 build_number(整数)而不是 version_name 做比较:语义化版本字符串的比较规则在客户端和
--    服务端各写一遍必然分叉,单调递增的整数没有歧义。version_name 只用于展示。

-- ── 1) 视图统一前缀 ────────────────────────────────────────────────
DROP VIEW analytics_events;
DROP VIEW apm_events;
DROP VIEW security_events;

CREATE VIEW pulse_analytics_events AS
SELECT id, event_id, session_id, ts, kind, name, source_module, attributes, created_at,
       project_id, device_id, installation_id, user_id
FROM pulse_events
WHERE kind = 'Analytics';

CREATE VIEW pulse_apm_events AS
SELECT id, event_id, session_id, ts, kind, name, source_module, attributes, created_at,
       project_id, device_id, installation_id, user_id
FROM pulse_events
WHERE kind IN ('Crash', 'Error', 'Log', 'Network', 'Performance', 'Breadcrumb');

CREATE VIEW pulse_security_events AS
SELECT id, event_id, session_id, ts, kind, name, source_module, attributes, created_at,
       project_id, device_id, installation_id, user_id
FROM pulse_events
WHERE kind = 'Runtime';

-- ── 2) 应用 ───────────────────────────────────────────────────────
CREATE TABLE pulse_apps (
    id BIGSERIAL PRIMARY KEY,
    -- 展示名,如「积分商城 iOS」。
    name VARCHAR(128) NOT NULL,
    -- ios / android / harmony / web。放字符串而不是枚举码:新增平台不必改库。
    platform VARCHAR(16) NOT NULL,
    -- 上报事件里的 project_id,把事件流和这条 App 记录连起来。
    app_key VARCHAR(64) NOT NULL,
    -- iOS bundle id / Android applicationId / 鸿蒙 bundleName;Web 留空。
    package_name VARCHAR(191),
    remark VARCHAR(255),
    -- 1 启用 / 0 停用。停用后仍保留历史数据,只是不再参与更新检查。
    status INT NOT NULL DEFAULT 1,
    created_at BIGINT,
    updated_at BIGINT
);
-- 同一平台下 app_key 唯一:更新检查就是按 (platform, app_key) 定位的,必须能唯一命中。
CREATE UNIQUE INDEX uk_pulse_apps_platform_key ON pulse_apps(platform, app_key);
CREATE INDEX idx_pulse_apps_package ON pulse_apps(package_name);

-- ── 3) 版本 ───────────────────────────────────────────────────────
CREATE TABLE pulse_app_versions (
    id BIGSERIAL PRIMARY KEY,
    app_id BIGINT NOT NULL,
    -- 展示用,如 "1.4.2"。
    version_name VARCHAR(64) NOT NULL,
    -- 比较用,单调递增。
    build_number BIGINT NOT NULL,
    release_notes TEXT NOT NULL DEFAULT '',
    -- App Store / 应用市场 / 直接下载地址。
    download_url VARCHAR(512),
    -- true = 客户端必须更新后才能继续使用业务功能。
    force_update BOOLEAN NOT NULL DEFAULT FALSE,
    -- 0 草稿 / 1 已发布。只有已发布的版本参与更新检查。
    published INT NOT NULL DEFAULT 0,
    published_at BIGINT,
    created_at BIGINT,
    updated_at BIGINT
);
-- 同一 App 下 build_number 唯一:重复的构建号会让「哪个是最新」失去确定答案。
CREATE UNIQUE INDEX uk_pulse_app_versions_build ON pulse_app_versions(app_id, build_number);
-- 更新检查的主查询:按 app 取已发布版本里 build_number 最大的一条。
CREATE INDEX idx_pulse_app_versions_lookup ON pulse_app_versions(app_id, published, build_number);
