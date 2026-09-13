-- pulse 模块后台菜单种子（V002）。
--
-- 对外呈现为普通应用分析 / 质量监控后台（对标友盟 U-App + Bugly），命名中性。
-- component 与 neton-application-front-pulse/src/manifest.ts 的 page key 一一对应。
-- 不写死菜单 id：父子用模块内唯一菜单名连接。status 一律 1。
-- ─────────────────────────────────────────────────────────────
WITH lvl1 AS (
    INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
    VALUES
        ('Pulse 观测', '', 1, 0, '/pulse', NULL, 'ant-design:fund-outlined', 21, 1, (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint)
    RETURNING id, name
)
INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
SELECT v.name, v.permission, v.type,
       (SELECT p.id FROM lvl1 p WHERE p.name = v.parent_name),
       v.path, v.component, v.icon, v.sort, v.status, (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
FROM (VALUES
    ('概览',     'pulse:stats:view', 2, 'Pulse 观测', 'overview',    'pulse/overview/index',    'ant-design:dashboard-outlined',   1, 1),
    ('事件分析', 'pulse:event:list', 2, 'Pulse 观测', 'events',      'pulse/events/index',      'ant-design:bar-chart-outlined',   2, 1),
    ('崩溃分析', 'pulse:apm:list',   2, 'Pulse 观测', 'crashes',     'pulse/crashes/index',     'ant-design:bug-outlined',         3, 1),
    ('性能',     'pulse:apm:list',   2, 'Pulse 观测', 'performance', 'pulse/performance/index', 'ant-design:thunderbolt-outlined', 4, 1)
) AS v(name, permission, type, parent_name, path, component, icon, sort, status);
