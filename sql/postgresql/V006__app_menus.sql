-- pulse 模块（postgresql）—— 应用管理菜单与按钮权限（V006）。
--
-- 菜单挂在既有的「Pulse 观测」目录下,排在第一位:先有应用,才谈得上它的事件、崩溃和版本。
-- component 与 neton-application-front-pulse/src/manifest.ts 的 page key 一一对应。
--
-- 按钮级权限单独建 type=3 的节点,而不是让页面权限一权通吃:
--   pulse:app:list    查看应用与版本列表
--   pulse:app:save    新增/编辑应用与版本
--   pulse:app:delete  删除应用与版本
--   pulse:app:publish 发布/下架版本 —— 单独一权,因为发布会立刻影响线上所有客户端的
--                     更新提示,尤其是强制更新会直接挡住用户使用,不能和普通编辑同权。

WITH menu AS (
    INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
    SELECT '应用管理', 'pulse:app:list', 2,
           (SELECT id FROM system_menus WHERE name = 'Pulse 观测' AND type = 1 LIMIT 1),
           'apps', 'pulse/apps/index', 'ant-design:appstore-outlined', 0, 1,
           (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
    RETURNING id
)
INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
SELECT v.name, v.permission, 3, (SELECT id FROM menu), NULL, NULL, NULL, v.sort, 1,
       (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
FROM (VALUES
    ('新增/编辑', 'pulse:app:save',    1),
    ('删除',      'pulse:app:delete',  2),
    ('发布版本',  'pulse:app:publish', 3)
) AS v(name, permission, sort);
