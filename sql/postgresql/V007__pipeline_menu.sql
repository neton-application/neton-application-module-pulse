-- pulse 模块（postgresql）—— 采集管道菜单与权限（V007）。
--
-- 管道是本模块唯一会「悄悄丢数据」的地方,所以它必须可见:队列积压多少、写入/重试/进死信各多少、
-- 失败的原因是什么。死信保留的意义就在于一次坏发布或数据库抖动之后能人工恢复,而不是只能道歉。
--
-- 权限分两级:
--   pulse:pipeline:view     查看队列深度、计数与死信内容
--   pulse:pipeline:operate  重放 / 清空死信 —— 这是对采集链路的写操作,
--                           在根因修复前重放只会把死信再灌一遍,不该和只读同权。

WITH menu AS (
    INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
    SELECT '采集管道', 'pulse:pipeline:view', 2,
           (SELECT id FROM system_menus WHERE name = 'Pulse 观测' AND type = 1 LIMIT 1),
           'pipeline', 'pulse/pipeline/index', 'ant-design:cloud-server-outlined', 5, 1,
           (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
    RETURNING id
)
INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
SELECT '重放/清空死信', 'pulse:pipeline:operate', 3, (SELECT id FROM menu), NULL, NULL, NULL, 1, 1,
       (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint;
