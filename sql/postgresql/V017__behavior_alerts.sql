-- pulse 模块（postgresql）—— 审计告警区分「结构」与「动作」（V017）。
--
-- 客户端现在除了上报「随包分发了哪些镜像」(结构),还会上报「哪个镜像调用了哪个敏感 API」(动作)。
-- 两者都是「某个模块值得关注」,但性质不同:
--   结构告警回答「这个包里装了什么」—— 发版时就确定,变化很慢;
--   动作告警回答「它在运行时做了什么」—— 同一个包在不同版本、不同用户路径下可能完全不同。
-- 合成一条会让「SDWebImage 存在」和「SDWebImage 读了剪贴板」互相覆盖,所以用 kind 区分,
-- 并把 API 名并入唯一键。
--
-- api 为空表示结构告警,与 V008 建表时的行为一致,老数据不需要迁移。

ALTER TABLE pulse_module_alerts ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'module';
ALTER TABLE pulse_module_alerts ADD COLUMN api VARCHAR(128);
-- 累计调用次数;结构告警恒为 0。次数说明频率,occurrences 说明上报批次,两者不是一回事。
ALTER TABLE pulse_module_alerts ADD COLUMN call_count BIGINT NOT NULL DEFAULT 0;

-- 唯一键换成 (project, kind, module, api):同一模块的「存在」和「读剪贴板」「读 IDFA」
-- 是三条独立的告警,不能互相覆盖。
DROP INDEX uk_pulse_module_alerts;
CREATE UNIQUE INDEX uk_pulse_module_alerts
    ON pulse_module_alerts(project_id, kind, module_name, COALESCE(api, ''));
