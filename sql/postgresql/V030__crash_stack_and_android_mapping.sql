-- pulse 模块（postgresql）—— Android 崩溃堆栈反混淆（V030）。
--
-- iOS 原生崩溃靠 pulse_symbols（地址→函数名）符号化；Android 的 Java/Kotlin 崩溃不一样:
-- 客户端上报的是**文本堆栈**（`at a.b.c(SourceFile:1)`），经 R8 混淆后类名/方法名都是 a、b、c
-- 这种，不借助 mapping.txt 根本看不懂是哪段代码。
--
-- 两件事:
--   1. 把代表性文本堆栈落到 issue 上（此前只存了数值帧 sample_frames，Java 的文本栈被丢了，
--      于是即便上传 mapping 也没有东西可反混淆）。
--   2. 按 (app_key, build_number) 存一份 R8 mapping，读 issue 详情时反混淆文本栈。
--      mapping 和符号表一样按构建归属:同一份混淆名只在生成它的那个构建里成立。

ALTER TABLE pulse_crash_issues ADD COLUMN IF NOT EXISTS sample_stack TEXT NOT NULL DEFAULT '';

-- R8/ProGuard mapping.txt，整份按构建存。反混淆是读时做（和符号化同理:mapping 可能在崩溃
-- 之后才补传，落库的反混淆结果会过期，而原始混淆栈永远有效）。
CREATE TABLE IF NOT EXISTS pulse_android_mappings (
    id BIGSERIAL PRIMARY KEY,
    app_key VARCHAR(64) NOT NULL,
    build_number BIGINT NOT NULL,
    -- 原始 mapping.txt 全文。
    mapping TEXT NOT NULL,
    created_at BIGINT
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pulse_android_mappings ON pulse_android_mappings(app_key, build_number);
