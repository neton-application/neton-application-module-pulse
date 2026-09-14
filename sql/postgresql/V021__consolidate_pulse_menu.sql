-- Pulse 后台菜单收口。
--
-- 早期的 telemetry 原型和正式 Pulse 模块各注册了一套一级菜单，造成概览、事件、
-- 告警和插件相关能力重复。telemetry 已不再承接客户端数据；这里仅隐藏其菜单，保留
-- 表与历史记录，避免破坏性删除。
--
-- Pulse 内部也去掉两个面向实现的重复入口：
--   * 原始崩溃明细仍可由事件页查询，菜单以可处置、可符号化的聚合页为主；
--   * 应用版本维护继续从应用管理页进入，版本分析保留为独立观察入口。

-- 禁用旧「安全遥测」整棵菜单树。新环境不再装配 telemetry 模块时，这段是安全的空操作。
WITH RECURSIVE legacy_menu AS (
    SELECT id
    FROM system_menus
    WHERE parent_id = 0 AND path = '/telemetry'

    UNION ALL

    SELECT child.id
    FROM system_menus child
    JOIN legacy_menu parent ON child.parent_id = parent.id
)
UPDATE system_menus
SET status = 0,
    updated_at = (extract(epoch from now()) * 1000)::bigint
WHERE id IN (SELECT id FROM legacy_menu);

-- 一级入口覆盖应用、版本、稳定性与安全能力，用「Pulse」比「Pulse 观测」更准确。
UPDATE system_menus
SET name = 'Pulse',
    sort = 21,
    updated_at = (extract(epoch from now()) * 1000)::bigint
WHERE parent_id = 0 AND path = '/pulse';

-- 按后台日常工作顺序排列，并使用对用户有意义的名称。
UPDATE system_menus
SET name = CASE component
        WHEN 'pulse/overview/index'     THEN '总览'
        WHEN 'pulse/apps/index'         THEN '应用管理'
        WHEN 'pulse/events/index'       THEN '事件'
        WHEN 'pulse/versions/index'     THEN '版本分析'
        WHEN 'pulse/crash-issues/index' THEN '崩溃分析'
        WHEN 'pulse/performance/index'  THEN '性能'
        WHEN 'pulse/audit/index'        THEN '安全审计'
        WHEN 'pulse/alerts/index'       THEN '告警'
        WHEN 'pulse/pipeline/index'     THEN '采集管道'
        ELSE name
    END,
    sort = CASE component
        WHEN 'pulse/overview/index'     THEN 1
        WHEN 'pulse/apps/index'         THEN 2
        WHEN 'pulse/events/index'       THEN 3
        WHEN 'pulse/versions/index'     THEN 4
        WHEN 'pulse/crash-issues/index' THEN 5
        WHEN 'pulse/performance/index'  THEN 6
        WHEN 'pulse/audit/index'        THEN 7
        WHEN 'pulse/alerts/index'       THEN 8
        WHEN 'pulse/pipeline/index'     THEN 9
        ELSE sort
    END,
    updated_at = (extract(epoch from now()) * 1000)::bigint
WHERE parent_id = (SELECT id FROM system_menus WHERE parent_id = 0 AND path = '/pulse' LIMIT 1)
  AND component IN (
      'pulse/overview/index',
      'pulse/apps/index',
      'pulse/events/index',
      'pulse/versions/index',
      'pulse/crash-issues/index',
      'pulse/performance/index',
      'pulse/audit/index',
      'pulse/alerts/index',
      'pulse/pipeline/index'
  );

-- 原始崩溃列表与聚合后的崩溃问题重复；保留路由与权限，隐藏菜单入口。
UPDATE system_menus
SET status = 0,
    updated_at = (extract(epoch from now()) * 1000)::bigint
WHERE parent_id = (SELECT id FROM system_menus WHERE parent_id = 0 AND path = '/pulse' LIMIT 1)
  AND component = 'pulse/crashes/index';
