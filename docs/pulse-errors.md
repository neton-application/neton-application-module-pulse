# module-pulse 错误码

## 规则

pulse 模块所有业务错误（Neton 标准信封）的 `message` 都是模块自己的机器码（`^[A-Z][A-Z0-9_]{2,}$`），统一定义在
`src/commonMain/kotlin/logic/PulseErrorCodes.kt`；不写中英文句子、不拼 `CODE: detail`、不做字符串插值（id、App ID、包名、
构建号等细节只进日志）。框架规则见 Neton `spec/error-codes.md`（ERROR_CODE_SPEC）。

数字 `code` 取 `NetonErrorCode` 的类别：参数非法 INVALID_PARAMS（400）、不存在 RESOURCE_NOT_FOUND（404）、
唯一性冲突 RESOURCE_ALREADY_EXISTS（409）。跟某个请求字段有关的错误另带字段错误
`errors = [{ field, message: <CODE>, code: <CODE>, params? }]`，`params` 是客户端渲染用的约束值（`min` / `max`），只随字段错误下发；不带字段的码（如制品大小上限）在前端语言包里直接写数值。
模块内抛错统一走 `PulseErrorCodes.kt` 里的 `pulseInvalid / pulseNotFound / pulseTaken`，实参只能是本表常量。

后台前端（neton-application-front-pulse）在 `src/locales/<lang>.ts` 里按 `error.<CODE>` 翻译（zh-CN + en-US），页面通过
`useDescribeError("pulse")` / `errorNamespace: "pulse"` 取词条。守卫测试
`src/macosArm64Test/kotlin/logic/ErrorMessageGuardTest.kt` 用共享夹具 `contract.testing.ErrorMessageGuard` 扫描
`src/commonMain`（含 `@RateLimit` 与校验注解的 message），句子会让测试失败。

新增码：先加到 `PulseErrorCodes`，再补本表与前端 zh-CN / en-US 两份 `error.<CODE>`。

### 不在本表的

- **msgtrans 线协议**：长连接上的 `PulseWireResponse(code, msg)`（CLIENT_CONNECT / 事件批次 / 心跳 / 更新检查）是 Pulse
  自己的协议（pulsekit `WIRE_PROTOCOL.md`），不是 Neton 信封。SDK 只按数字 `code`（400 / 403 / 404 / 501 / 503）分支，
  `msg` 是给人看的说明，格式保持不变（如 `unknown App ID '<key>'`）；死信队列记录的拒绝原因同样沿用这段说明。
- **部署 / 运行环境故障**：服务器缺 `/usr/bin/unzip`、无法创建分析临时目录、制品写盘失败——抛 `IllegalStateException`，
  走框架 500 `INTERNAL_ERROR`，细节只进日志。
- 框架标准码（`VALIDATION_FAILED`、`INVALID_JSON`、`PERMISSION_DENIED`…）由前端运行时的通用语言包翻译。

## 错误码

| 机器码 | NetonErrorCode | 场景 | 字段 / params |
| --- | --- | --- | --- |
| `APP_NOT_FOUND` | RESOURCE_NOT_FOUND | 更新应用时 id 不存在；新建 / 更新版本时 appId 不存在；分析制品时版本所属应用不存在 | — |
| `APP_NAME_REQUIRED` | INVALID_PARAMS | 应用名称为空 | `name` |
| `APP_KEY_REQUIRED` | INVALID_PARAMS | 应用标识（App ID）为空：更新应用、上传 R8 mapping、上传符号表 | `appKey` |
| `APP_PLATFORM_INVALID` | INVALID_PARAMS | 平台不是 ios / android / harmony / web 之一 | `platform` |
| `APP_PACKAGE_POLICY_INVALID` | INVALID_PARAMS | 包名策略不是 strict / allowlist / any 之一 | `packagePolicy` |
| `APP_PACKAGE_NAME_REQUIRED` | INVALID_PARAMS | 非 web 平台且包名策略不是 any 时包名为空 | `packageName` |
| `APP_KEY_TAKEN` | RESOURCE_ALREADY_EXISTS | 应用标识已被其他应用使用 | `appKey` |
| `VERSION_NOT_FOUND` | RESOURCE_NOT_FOUND | 更新 / 发布版本、分析制品时版本 id 不存在 | — |
| `VERSION_ID_REQUIRED` | INVALID_PARAMS | 制品分析请求缺少有效的 versionId（表单或 query） | `versionId` |
| `VERSION_NAME_REQUIRED` | INVALID_PARAMS | 版本名为空 | `versionName` |
| `BUILD_NUMBER_INVALID` | INVALID_PARAMS | 构建号 ≤ 0：新建 / 更新版本、上传 mapping、上传符号表 | `buildNumber`，`min=1` |
| `BUILD_NUMBER_TAKEN` | RESOURCE_ALREADY_EXISTS | 同一应用下构建号已存在 | `buildNumber` |
| `CRASH_ISSUE_NOT_FOUND` | RESOURCE_NOT_FOUND | 修改崩溃问题状态时 id 不存在 | — |
| `MAPPING_EMPTY` | INVALID_PARAMS | 上传的 R8 / ProGuard mapping 解析不出任何类映射 | — |
| `SYMBOLS_EMPTY` | INVALID_PARAMS | 上传的符号表（`nm -n` 输出）解析不出任何符号 | — |
| `ALERT_RULE_NAME_REQUIRED` | INVALID_PARAMS | 告警规则名称为空 | `name` |
| `ALERT_RULE_TYPE_INVALID` | INVALID_PARAMS | 告警规则类型不是 crash_free_rate / new_issue_impact | `type` |
| `ALERT_CRASH_FREE_RATE_OUT_OF_RANGE` | INVALID_PARAMS | 无崩溃率阈值不在 (0, 100] | `threshold`，`min=0`、`max=100` |
| `ALERT_IMPACT_THRESHOLD_TOO_SMALL` | INVALID_PARAMS | 新问题影响设备数阈值 < 1 | `threshold`，`min=1` |
| `ALERT_MIN_DEVICES_TOO_SMALL` | INVALID_PARAMS | 最小样本量 < 1 | `minDevices`，`min=1` |
| `ALERT_COOLDOWN_NEGATIVE` | INVALID_PARAMS | 冷却时间为负 | `cooldownMs`，`min=0` |
| `AUDIT_RULE_PATTERN_REQUIRED` | INVALID_PARAMS | 模块审计规则匹配模式为空 | `pattern` |
| `AUDIT_RULE_SEVERITY_INVALID` | INVALID_PARAMS | 审计规则级别不是 info / warn / critical | `severity` |
| `MODULE_ALERT_NOT_FOUND` | RESOURCE_NOT_FOUND | 修改模块审计告警状态时 id 不存在 | — |
| `ARTIFACT_PLATFORM_NOT_IOS` | INVALID_PARAMS | 非 iOS 应用的版本上传 IPA / XCArchive | — |
| `ARTIFACT_FILE_REQUIRED` | INVALID_PARAMS | multipart 上传缺少 `artifact` 文件 | `artifact` |
| `ARTIFACT_FILE_NAME_REQUIRED` | INVALID_PARAMS | 原始字节上传缺少 `fileName` query | `fileName` |
| `ARTIFACT_SIZE_INVALID` | INVALID_PARAMS | 上传大小不在 1 字节 – 512 MiB | — |
| `ARTIFACT_EMPTY` | INVALID_PARAMS | 制品文件为空 | — |
| `ARTIFACT_TOO_LARGE` | INVALID_PARAMS | 制品超过 512 MiB | — |
| `ARTIFACT_FORMAT_UNSUPPORTED` | INVALID_PARAMS | 文件名不是 `.ipa` / `.zip`（压缩后的 `.xcarchive`） | — |
| `ARTIFACT_UNZIP_FAILED` | INVALID_PARAMS | unzip 解压失败（不是有效的 IPA / XCArchive ZIP） | — |
| `ARTIFACT_EXPANDED_TOO_LARGE` | INVALID_PARAMS | 解压后（声明或实际扫描）超过 2 GiB 安全上限 | — |
| `ARTIFACT_TOO_MANY_IMAGES` | INVALID_PARAMS | Mach-O 镜像数量超过安全上限 | — |
| `ARTIFACT_IMAGE_TOO_LARGE` | INVALID_PARAMS | 单个 Mach-O 镜像超过 512 MiB | — |
| `ARTIFACT_NO_RELEASE_IMAGE` | INVALID_PARAMS | 制品内没有发布用 Mach-O 镜像 | — |
| `ARTIFACT_ZIP_INVALID` | INVALID_PARAMS | 找不到 ZIP 结尾记录，不是有效 ZIP | — |
| `ARTIFACT_ZIP_MULTI_DISK` | INVALID_PARAMS | 分卷 ZIP | — |
| `ARTIFACT_ZIP_DIRECTORY_CORRUPT` | INVALID_PARAMS | 中央目录条目数 / 偏移 / 长度异常 | — |
| `ARTIFACT_ZIP_ENTRY_CORRUPT` | INVALID_PARAMS | 中央目录条目头损坏 | — |
| `ARTIFACT_ZIP64_UNSUPPORTED` | INVALID_PARAMS | ZIP64 或条目越界 | — |
| `ARTIFACT_ZIP_ENCRYPTED` | INVALID_PARAMS | 加密 ZIP | — |
| `ARTIFACT_ZIP_UNSAFE_PATH` | INVALID_PARAMS | 条目路径为空、绝对路径、含 `..` / NUL 或重复 | — |
| `ARTIFACT_ZIP_SYMLINK` | INVALID_PARAMS | 条目是符号链接 | — |
| `INGEST_APP_ID_MISSING` | INVALID_PARAMS | HTTP 接入 `/platform/pulse/ingest`：批次只带了平台或包名其中之一，缺 App ID | — |
| `INGEST_APP_ID_UNKNOWN` | INVALID_PARAMS | HTTP 接入：App ID 未注册（或该平台下没有） | — |
| `INGEST_APP_ID_DISABLED` | INVALID_PARAMS | HTTP 接入：App ID 已停用 | — |
| `INGEST_PACKAGE_NOT_REGISTERED` | INVALID_PARAMS | HTTP 接入：没有 App ID 时，包名未在该平台注册 | — |
| `INGEST_PACKAGE_NOT_ALLOWED` | INVALID_PARAMS | HTTP 接入：包名不被该 App ID 的包名策略接受 | — |
| `INGEST_RATE_LIMITED` | RATE_LIMIT_EXCEEDED（10300） | `@RateLimit`：HTTP 接入按 IP 每分钟 120 次 | — |
| `UPDATE_CHECK_RATE_LIMITED` | RATE_LIMIT_EXCEEDED（10300） | `@RateLimit`：HTTP 更新检查按 IP 每分钟 60 次 | — |

HTTP 更新检查（`/platform/pulse/update-check`）失败时照旧 fail-open 返回 `action = none`，不抛错。

## 破坏性变更

- 所有 pulse 后台接口错误的 `message` 由中文句子改为上表机器码；「不存在」类由 400 改为 404，
  应用标识 / 构建号重复由 400 改为 409（`RESOURCE_ALREADY_EXISTS`）。
- HTTP 接入被应用策略拒绝时，信封 `message` 由英文说明（如 `unknown App ID 'x'`）改为 `INGEST_*` 机器码，HTTP 仍是 400。
  msgtrans 线协议的 `msg` 不变。
- `@RateLimit` 命中：`message` 由英文句子改为 `INGEST_RATE_LIMITED` / `UPDATE_CHECK_RATE_LIMITED`。
