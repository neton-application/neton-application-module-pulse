-- pulse 模块（postgresql）—— 三方插件审计（V008）。
--
-- 客户端在 module_inventory 事件里上报「随包一起分发的镜像」名单(应用自身 framework +
-- 随包的三方 SDK),系统库只报数量与摘要。这里对这份名单做规则匹配并产生告警。
--
-- 判定放在服务端而不是客户端,原因和整个 PulseKit 的中性命名原则一致:
--   * 客户端只陈述「加载了什么」,不携带任何结论 —— 发布出去的二进制里不该有一份「可疑清单」;
--   * 规则要能随时调整,不能等下一次发版;
--   * 同一个模块在不同 App 里的性质可能完全不同,判定需要上下文。
--
-- 规则是「模式 + 分级」,不是黑名单:大多数三方 SDK 不是恶意的,只是需要被知道存在。

CREATE TABLE pulse_module_rules (
    id BIGSERIAL PRIMARY KEY,
    -- 匹配模块名,支持前后缀通配 '*'。如 'SDWebImage'、'*Ads*'、'WeChat*'。
    pattern VARCHAR(191) NOT NULL,
    -- 展示名,如「微信 SDK」。
    label VARCHAR(128) NOT NULL DEFAULT '',
    -- info 记录在案 / warn 需要关注 / critical 需要立刻处理。
    severity VARCHAR(16) NOT NULL DEFAULT 'info',
    -- 为什么值得关注:合规、隐私、体积、已知漏洞……由运营填写。
    note VARCHAR(512),
    -- 限定只对某个 App 生效;NULL 表示全局规则。同一模块在不同产品里的性质可能不同。
    app_id BIGINT,
    status INT NOT NULL DEFAULT 1,
    created_at BIGINT,
    updated_at BIGINT
);
CREATE INDEX idx_pulse_module_rules_app ON pulse_module_rules(app_id, status);

-- 告警按 (project, platform, 模块名, 规则) 聚合,而不是每个事件一条。
-- 一个模块在一万台设备上出现是一条告警加一个计数,不是一万条告警 —— 后者没人看得完。
CREATE TABLE pulse_module_alerts (
    id BIGSERIAL PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL DEFAULT '',
    module_name VARCHAR(191) NOT NULL,
    rule_id BIGINT,
    severity VARCHAR(16) NOT NULL DEFAULT 'info',
    label VARCHAR(128) NOT NULL DEFAULT '',
    note VARCHAR(512),
    first_seen_at BIGINT NOT NULL DEFAULT 0,
    last_seen_at BIGINT NOT NULL DEFAULT 0,
    -- 出现次数与去重设备数:前者说明频率,后者说明影响面,两个都要。
    occurrences BIGINT NOT NULL DEFAULT 0,
    device_count BIGINT NOT NULL DEFAULT 0,
    -- 0 待处理 / 1 已确认(知道了,可接受) / 2 已忽略。
    status INT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
);
CREATE UNIQUE INDEX uk_pulse_module_alerts ON pulse_module_alerts(project_id, module_name);
CREATE INDEX idx_pulse_module_alerts_status ON pulse_module_alerts(status, severity, last_seen_at);

-- 去重设备靠这张明细表,而不是在告警行上塞一个集合。
-- 没有它,device_count 只能靠事件数近似,而同一台设备每次启动都会上报一次。
CREATE TABLE pulse_module_sightings (
    id BIGSERIAL PRIMARY KEY,
    alert_id BIGINT NOT NULL,
    device_id VARCHAR(64) NOT NULL DEFAULT '',
    app_version VARCHAR(64),
    first_seen_at BIGINT NOT NULL DEFAULT 0,
    last_seen_at BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_pulse_module_sightings ON pulse_module_sightings(alert_id, device_id);
