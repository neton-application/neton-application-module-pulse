-- 分析页回到侧边栏(V028)。
--
-- V023 把事件、版本分析、崩溃分析、性能四个页面从侧边栏收起,只留总览上的入口链接。
-- 实际使用下来这条路走不通:总览是一个汇总页,它的链接在页面中段,新来的人既不知道有这些页,
-- 也说不清每个页看的是什么。入口藏起来并不会让后台更简洁,只会让功能看起来不存在。
UPDATE system_menus
SET visible = TRUE,
    updated_at = (extract(epoch from now()) * 1000)::bigint
WHERE parent_id = (SELECT id FROM system_menus WHERE parent_id = 0 AND path = '/pulse' LIMIT 1)
  AND component IN (
      'pulse/events/index',
      'pulse/versions/index',
      'pulse/crash-issues/index',
      'pulse/performance/index'
  );
