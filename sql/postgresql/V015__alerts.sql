-- pulse 模块（postgresql）—— 告警规则与告警记录（V015）。
--
-- 前面几页都要求人主动登录后台才能发现问题。告警是唯一让问题主动找上人的环节,
-- 所以它的判断标准必须是「值得半夜叫醒人」的那种,而不是任何波动都报。
--
-- 两类规则,对应两个真实问题:
--   crash_free_rate  某构建的无崩溃设备率跌破阈值 —— 回答「这一版能不能继续放量」
--   new_issue_impact 出现影响设备数超过阈值的新问题 —— 回答「有没有出现新的大面积故障」
--
-- 两个防误报机制,缺一不可:
--   min_devices  样本不足不报。新版本刚放量时只有几台设备,一台崩溃就是 50%,
--                没有这个门槛,每次发版都会先收到一串假警报,然后人就不看告警了。
--   cooldown_ms  同一规则在冷却期内不重复报。崩溃是持续发生的,没有冷却会变成刷屏。
--
-- 通知走 webhook(POST JSON)。记录落库是为了「发过什么、发成功没有」可查:
-- 一个没人能验证是否真的发出去过的告警系统,和没有告警是一样的。

CREATE TABLE pulse_alert_rules (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    -- crash_free_rate | new_issue_impact
    type VARCHAR(32) NOT NULL,
    -- 限定项目(= 上报的 app_key);NULL 表示全部。
    project_id VARCHAR(64),
    -- crash_free_rate: 百分比下限(如 99.0);new_issue_impact: 设备数下限(如 10)。
    threshold DOUBLE PRECISION NOT NULL DEFAULT 0,
    -- 样本不足不报,见上。
    min_devices BIGINT NOT NULL DEFAULT 10,
    -- 同规则冷却期,默认 1 小时。
    cooldown_ms BIGINT NOT NULL DEFAULT 3600000,
    webhook_url VARCHAR(512),
    status INT NOT NULL DEFAULT 1,
    -- 最近一次真正触发的时间,冷却判断用它。
    last_fired_at BIGINT,
    created_at BIGINT,
    updated_at BIGINT
);
CREATE INDEX idx_pulse_alert_rules_active ON pulse_alert_rules(status, type);

CREATE TABLE pulse_alert_records (
    id BIGSERIAL PRIMARY KEY,
    rule_id BIGINT NOT NULL,
    rule_name VARCHAR(128) NOT NULL DEFAULT '',
    type VARCHAR(32) NOT NULL,
    project_id VARCHAR(64),
    -- 一句话说清发生了什么,webhook 和后台都用它。
    title VARCHAR(255) NOT NULL DEFAULT '',
    detail TEXT NOT NULL DEFAULT '',
    -- 触发时的实测值与阈值,便于事后判断规则是否该调整。
    observed DOUBLE PRECISION NOT NULL DEFAULT 0,
    threshold DOUBLE PRECISION NOT NULL DEFAULT 0,
    -- 0 待发送 / 1 已送达 / 2 发送失败 / 3 无需发送(未配置 webhook)
    delivery_status INT NOT NULL DEFAULT 0,
    delivery_error VARCHAR(512),
    fired_at BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT
);
CREATE INDEX idx_pulse_alert_records_time ON pulse_alert_records(fired_at DESC);
CREATE INDEX idx_pulse_alert_records_rule ON pulse_alert_records(rule_id, fired_at DESC);
