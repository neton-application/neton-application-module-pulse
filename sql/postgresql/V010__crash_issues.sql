-- pulse 模块（postgresql）—— 崩溃聚合与堆栈符号化（V010）。
--
-- 一次崩溃在一万台设备上发生,是**一个**问题,不是一万条记录。原始事件仍然全量保留在
-- pulse_events(那是事实),这里保存的是从事实推导出来的「问题」视图:同一处崩溃归并成一条,
-- 带影响设备数、首末次出现、状态流转。推导物可以随时重建,所以聚合失败不能反过来影响入库。
--
-- 归并键是**指纹**,由栈顶若干帧的静态地址算出,而不是崩溃信息文本:
--   * 同一个 bug 在不同设备上的 message 常常带着不同的地址、路径、参数,按文本归并会散成一片;
--   * 栈顶几帧决定了「崩在哪」,这正是开发要找的东西;
--   * 静态地址(运行地址 - slide)在同一构建里是稳定的,运行地址不是 —— ASLR 每次启动都不同。
-- 没有栈帧时(如平台不支持 backtrace)退化为按 name + message 归并,总比不归并强。

CREATE TABLE pulse_crash_issues (
    id BIGSERIAL PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL DEFAULT '',
    -- 归并键,见上。
    fingerprint VARCHAR(64) NOT NULL,
    -- 展示用:崩溃信号名或异常名。
    title VARCHAR(191) NOT NULL DEFAULT '',
    message VARCHAR(512) NOT NULL DEFAULT '',
    -- 代表性堆栈:第一次见到时的原始帧,符号化在读取时做,不落库。
    -- 落库符号会在补传 dSYM 后过期,而原始地址永远有效。
    sample_frames TEXT NOT NULL DEFAULT '',
    sample_slide BIGINT NOT NULL DEFAULT 0,
    -- 归因到具体构建,符号表也是按构建存的。
    app_key VARCHAR(64),
    build_number BIGINT,
    first_seen_at BIGINT NOT NULL DEFAULT 0,
    last_seen_at BIGINT NOT NULL DEFAULT 0,
    event_count BIGINT NOT NULL DEFAULT 0,
    device_count BIGINT NOT NULL DEFAULT 0,
    -- 0 待处理 / 1 处理中 / 2 已解决 / 3 已忽略。
    status INT NOT NULL DEFAULT 0,
    created_at BIGINT,
    updated_at BIGINT
);
CREATE UNIQUE INDEX uk_pulse_crash_issues ON pulse_crash_issues(project_id, fingerprint);
CREATE INDEX idx_pulse_crash_issues_rank ON pulse_crash_issues(status, device_count, last_seen_at);

-- 去重设备:和插件审计同样的理由,device_count 不能用事件数近似。
CREATE TABLE pulse_crash_sightings (
    id BIGSERIAL PRIMARY KEY,
    issue_id BIGINT NOT NULL,
    device_id VARCHAR(64) NOT NULL DEFAULT '',
    build_number BIGINT,
    first_seen_at BIGINT NOT NULL DEFAULT 0,
    last_seen_at BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_pulse_crash_sightings ON pulse_crash_sightings(issue_id, device_id);

-- 符号表。按 (app_key, build_number) 存,因为同一份地址只在同一个构建里有意义。
-- 一行一个符号的起始地址;查某个地址落在哪个符号里 = 找 <= 它的最大 address。
-- 这里存的是 nm/atos 能导出的东西,不解析 Mach-O:解析 dSYM 是另一个量级的工作,
-- 而符号起始地址表已经足够把地址变成函数名。
CREATE TABLE pulse_symbols (
    id BIGSERIAL PRIMARY KEY,
    app_key VARCHAR(64) NOT NULL,
    build_number BIGINT NOT NULL,
    -- 静态地址(与 slide 无关)。
    address BIGINT NOT NULL,
    symbol VARCHAR(512) NOT NULL
);
-- 符号化的主查询:同一构建内按地址倒序找第一条 <= 目标地址。
CREATE INDEX idx_pulse_symbols_lookup ON pulse_symbols(app_key, build_number, address);
