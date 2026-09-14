-- pulse 模块（postgresql）—— 崩溃聚合菜单与权限（V011）。
--
-- 与既有的「崩溃分析」(原始明细) 并存:明细回答「这一次发生了什么」,
-- 聚合回答「什么在坏、坏得多严重、修好了没有」。两者看的是同一批事件的不同问题。
--
-- 权限分三级:
--   pulse:crash:list     查看问题列表与堆栈
--   pulse:crash:triage   流转状态(处理中/已解决/忽略)
--   pulse:crash:symbols  上传符号表 —— 单独一权,因为传错一份符号表会让该构建的所有堆栈
--                        变成「看起来对但其实指错函数」,比读不出来更危险

WITH menu AS (
    INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
    SELECT '崩溃聚合', 'pulse:crash:list', 2,
           (SELECT id FROM system_menus WHERE name = 'Pulse 观测' AND type = 1 LIMIT 1),
           'crash-issues', 'pulse/crash-issues/index', 'ant-design:fire-outlined', 3, 1,
           (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
    RETURNING id
)
INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
SELECT v.name, v.permission, 3, (SELECT id FROM menu), NULL, NULL, NULL, v.sort, 1,
       (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
FROM (VALUES
    ('流转状态',   'pulse:crash:triage',  1),
    ('上传符号表', 'pulse:crash:symbols', 2)
) AS v(name, permission, sort);
