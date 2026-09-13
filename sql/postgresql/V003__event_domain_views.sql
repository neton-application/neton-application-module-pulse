-- pulse 模块（postgresql）—— 按业务域拆分事件读取面（V003）。
--
-- pulse_events 是唯一的落库表；本迁移在其上建立三个业务域视图，把 kind → 业务域的归类
-- 集中到一处 SQL，而不是散落在查询逻辑、控制器和前端里各写一份 kind 列表。
--
--   analytics_events —— 产品分析：用户行为事件
--   apm_events       —— 质量监控：崩溃、错误、日志、网络、性能、面包屑
--   security_events  —— 运行时安全：第三方模块加载、敏感行为等运行时归因
--
-- 为什么是视图而不是三张物理表：读侧（PulseQueryLogic、三个 admin controller）整个建立在
-- pulse_events 之上，ORM 也从这里写入；物理拆分需要重写读写两侧，而当前三域并没有各自
-- 独立的列。视图代价为零，且随时可以在不改调用方的前提下换成物化视图或物理表。
--
-- 视图不提供的东西，明确写在这里以免误解：三域共用一份存储，因此**没有各自独立的保留/清理
-- 策略**。若将来 analytics 的量级需要比 apm 更早过期，就要真正做物理拆分。
--
-- kind 取值见 pulse-core 的 EventKind：
--   Analytics / Crash / Error / Log / Network / Performance / Runtime / Breadcrumb
-- 未归类的 kind 不会出现在任何视图里，这是有意的：新增 kind 必须显式决定它属于哪个域。

CREATE VIEW analytics_events AS
SELECT id, event_id, session_id, ts, kind, name, source_module, attributes, created_at
FROM pulse_events
WHERE kind = 'Analytics';

CREATE VIEW apm_events AS
SELECT id, event_id, session_id, ts, kind, name, source_module, attributes, created_at
FROM pulse_events
WHERE kind IN ('Crash', 'Error', 'Log', 'Network', 'Performance', 'Breadcrumb');

CREATE VIEW security_events AS
SELECT id, event_id, session_id, ts, kind, name, source_module, attributes, created_at
FROM pulse_events
WHERE kind = 'Runtime';
