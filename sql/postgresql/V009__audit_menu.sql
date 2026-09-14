-- pulse 模块（postgresql）—— 三方插件审计菜单与权限（V009）。
--
-- 权限分三级,对应三类不同的人在不同频率上做的事:
--   pulse:audit:list    查看告警与规则（日常）
--   pulse:audit:triage  确认/忽略告警（日常处置）
--   pulse:audit:save    增删改规则（偶尔的策略调整 —— 改规则等于改「什么算问题」,
--                       影响面比处置单条告警大得多,不该同权）

WITH menu AS (
    INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
    SELECT '插件审计', 'pulse:audit:list', 2,
           (SELECT id FROM system_menus WHERE name = 'Pulse 观测' AND type = 1 LIMIT 1),
           'audit', 'pulse/audit/index', 'ant-design:safety-certificate-outlined', 6, 1,
           (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
    RETURNING id
)
INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
SELECT v.name, v.permission, 3, (SELECT id FROM menu), NULL, NULL, NULL, v.sort, 1,
       (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
FROM (VALUES
    ('处置告警', 'pulse:audit:triage', 1),
    ('维护规则', 'pulse:audit:save',   2)
) AS v(name, permission, sort);
