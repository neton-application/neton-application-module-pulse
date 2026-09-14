-- pulse 模块（postgresql）—— 告警菜单与权限（V016）。
--
-- 权限分两级:
--   pulse:alert:list  查看规则与告警历史
--   pulse:alert:save  增删改规则 + 立即评估 —— 「立即评估」会真的发出通知,
--                     不该让只读账号能顺手把值班的人叫起来,所以和改规则同权而不是和查看同权

WITH menu AS (
    INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
    SELECT '告警', 'pulse:alert:list', 2,
           (SELECT id FROM system_menus WHERE name = 'Pulse 观测' AND type = 1 LIMIT 1),
           'alerts', 'pulse/alerts/index', 'ant-design:alert-outlined', 7, 1,
           (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
    RETURNING id
)
INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
SELECT '维护规则', 'pulse:alert:save', 3, (SELECT id FROM menu), NULL, NULL, NULL, 1, 1,
       (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint;
