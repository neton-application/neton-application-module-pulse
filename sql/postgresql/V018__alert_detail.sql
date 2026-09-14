-- pulse 模块（postgresql）—— 动作告警带上明细（V018）。
--
-- 网络请求的「目的地」是这条发现里最有价值的部分:同一个 SDK 连 a.example.com 和连一个
-- 陌生域名,是两件完全不同的事。没有这一列,它们会因为 (module, api) 相同而被合并成一条,
-- 恰好把最该看见的信息抹掉。
--
-- 客户端上报前已剥掉 query 与 fragment(见 NetworkDetail):URL 的查询串里常常带着
-- 会话令牌和个人信息,而本模块的规则是 attributes 只放元数据。这里存的是
-- scheme://host/path,足以回答「连了谁、什么类型的端点」,不会让遥测库变成凭证仓库。

ALTER TABLE pulse_module_alerts ADD COLUMN detail VARCHAR(512);

DROP INDEX uk_pulse_module_alerts;
CREATE UNIQUE INDEX uk_pulse_module_alerts
    ON pulse_module_alerts(project_id, kind, module_name, COALESCE(api, ''), COALESCE(detail, ''));
