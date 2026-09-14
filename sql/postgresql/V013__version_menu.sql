-- pulse 模块（postgresql）—— 版本对比菜单（V013）。
--
-- 复用 pulse:stats:view,不新造权限:这问的是概览同一个问题,只是按版本切开。
-- 能看全局数据的人,没有理由不能看它的版本分解。

INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
SELECT '版本对比', 'pulse:stats:view', 2,
       (SELECT id FROM system_menus WHERE name = 'Pulse 观测' AND type = 1 LIMIT 1),
       'versions', 'pulse/versions/index', 'ant-design:branches-outlined', 2, 1,
       (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint;
