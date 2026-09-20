-- 崩溃问题区分致命与非致命(V027)。
--
-- 聚合逻辑一直把 Crash 和 Error 两种事件归并进同一张表 —— 这是对的,它们的归并方式完全一样。
-- 但问题表本身没有留下「这是哪一种」,于是崩溃列表里混着被捕获的接口错误,
-- 而这两者的处理优先级差着一个数量级:崩溃是用户当场丢失了会话,错误是代码已经接住了。
-- 补上 kind 之后,列表才能按致命性筛选,存量数据按已有语义默认视为 Crash。
ALTER TABLE pulse_crash_issues ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'Crash';
CREATE INDEX idx_pulse_crash_issues_kind ON pulse_crash_issues(project_id, kind, last_seen_at);

-- 存量回填:问题的 title 就是事件的 name,凡是只以 Error 形态出现过的名字,归回 Error。
UPDATE pulse_crash_issues i SET kind = 'Error'
WHERE EXISTS (
    SELECT 1 FROM pulse_events e
    WHERE e.kind = 'Error' AND e.name = i.title AND COALESCE(e.project_id, '') = i.project_id
) AND NOT EXISTS (
    SELECT 1 FROM pulse_events e
    WHERE e.kind = 'Crash' AND e.name = i.title AND COALESCE(e.project_id, '') = i.project_id
);
