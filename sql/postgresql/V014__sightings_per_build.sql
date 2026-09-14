-- pulse 模块（postgresql）—— 崩溃命中记录按构建区分（V014）。
--
-- 原唯一键是 (issue_id, device_id):同一台设备在多个版本上命中同一个问题,只会留下**首次**那一条,
-- build_number 永远停在第一次的值。后果是版本对比会说谎 —— 一个在 140 和 141 上都存在的问题,
-- 因为设备的记录写着 140,在「141 相对 140 新增了什么」里被算成回归。
--
-- 改为 (issue_id, device_id, build_number):同一设备在每个版本各留一条。
-- 于是「某问题在某版本影响了多少设备」可以直接查,问题总设备数则用 DISTINCT device_id。
-- 多出来的行数是可接受的:一个设备最多贡献「它装过的版本数」条,而不是崩溃次数。

DROP INDEX uk_pulse_crash_sightings;
CREATE UNIQUE INDEX uk_pulse_crash_sightings
    ON pulse_crash_sightings(issue_id, device_id, build_number);

-- 按版本查该问题影响面的主查询。
CREATE INDEX idx_pulse_crash_sightings_build
    ON pulse_crash_sightings(build_number, issue_id);
