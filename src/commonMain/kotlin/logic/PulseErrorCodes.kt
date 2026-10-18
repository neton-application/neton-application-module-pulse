package logic

import neton.core.http.BadRequestException
import neton.core.http.HttpException
import neton.core.http.NetonErrorCode
import neton.core.http.NotFoundException
import neton.core.http.ValidationError

/**
 * pulse 模块的业务错误机器码（spec `docs/pulse-errors.md`）。
 *
 * 规则：Neton 信封的 `message` 只放这里的机器码（`^[A-Z][A-Z0-9_]{2,}$`），后台按
 * `error.<CODE>` 翻译（neton-application-front-pulse 的语言包）。数字 `code` 取 `NetonErrorCode` 的类别：
 * 参数非法 INVALID_PARAMS（400）、不存在 RESOURCE_NOT_FOUND（404）、重复 RESOURCE_ALREADY_EXISTS（409）。
 * 跟请求字段有关的错误另带字段错误 `ValidationError.of(<字段>, CODE, params)`；排障细节（id、包名、
 * 取值）只进日志。
 *
 * msgtrans 长连接上的 `PulseWireResponse(code, msg)` 是 Pulse 自己的线协议（pulsekit WIRE_PROTOCOL.md），
 * 不是 Neton 信封，不受本表约束，格式保持不变。
 *
 * 新增码：先加在这里，再补 spec 与前端 zh-CN / en-US 两份 `error.<CODE>`。
 */
object PulseErrorCodes {
    // ---- 应用 ----
    const val APP_NOT_FOUND = "APP_NOT_FOUND"
    const val APP_NAME_REQUIRED = "APP_NAME_REQUIRED"
    const val APP_KEY_REQUIRED = "APP_KEY_REQUIRED"
    const val APP_PLATFORM_INVALID = "APP_PLATFORM_INVALID"
    const val APP_PACKAGE_POLICY_INVALID = "APP_PACKAGE_POLICY_INVALID"
    const val APP_PACKAGE_NAME_REQUIRED = "APP_PACKAGE_NAME_REQUIRED"
    const val APP_KEY_TAKEN = "APP_KEY_TAKEN"

    // ---- 版本 ----
    const val VERSION_NOT_FOUND = "VERSION_NOT_FOUND"
    const val VERSION_ID_REQUIRED = "VERSION_ID_REQUIRED"
    const val VERSION_NAME_REQUIRED = "VERSION_NAME_REQUIRED"
    const val BUILD_NUMBER_INVALID = "BUILD_NUMBER_INVALID"
    const val BUILD_NUMBER_TAKEN = "BUILD_NUMBER_TAKEN"

    // ---- 崩溃 / 符号 ----
    const val CRASH_ISSUE_NOT_FOUND = "CRASH_ISSUE_NOT_FOUND"
    const val MAPPING_EMPTY = "MAPPING_EMPTY"
    const val SYMBOLS_EMPTY = "SYMBOLS_EMPTY"

    // ---- 告警规则 ----
    const val ALERT_RULE_NAME_REQUIRED = "ALERT_RULE_NAME_REQUIRED"
    const val ALERT_RULE_TYPE_INVALID = "ALERT_RULE_TYPE_INVALID"
    const val ALERT_CRASH_FREE_RATE_OUT_OF_RANGE = "ALERT_CRASH_FREE_RATE_OUT_OF_RANGE"
    const val ALERT_IMPACT_THRESHOLD_TOO_SMALL = "ALERT_IMPACT_THRESHOLD_TOO_SMALL"
    const val ALERT_MIN_DEVICES_TOO_SMALL = "ALERT_MIN_DEVICES_TOO_SMALL"
    const val ALERT_COOLDOWN_NEGATIVE = "ALERT_COOLDOWN_NEGATIVE"

    // ---- 模块审计 ----
    const val AUDIT_RULE_PATTERN_REQUIRED = "AUDIT_RULE_PATTERN_REQUIRED"
    const val AUDIT_RULE_SEVERITY_INVALID = "AUDIT_RULE_SEVERITY_INVALID"
    const val MODULE_ALERT_NOT_FOUND = "MODULE_ALERT_NOT_FOUND"

    // ---- iOS 制品分析 ----
    const val ARTIFACT_PLATFORM_NOT_IOS = "ARTIFACT_PLATFORM_NOT_IOS"
    const val ARTIFACT_FILE_REQUIRED = "ARTIFACT_FILE_REQUIRED"
    const val ARTIFACT_FILE_NAME_REQUIRED = "ARTIFACT_FILE_NAME_REQUIRED"
    const val ARTIFACT_SIZE_INVALID = "ARTIFACT_SIZE_INVALID"
    const val ARTIFACT_EMPTY = "ARTIFACT_EMPTY"
    const val ARTIFACT_TOO_LARGE = "ARTIFACT_TOO_LARGE"
    const val ARTIFACT_FORMAT_UNSUPPORTED = "ARTIFACT_FORMAT_UNSUPPORTED"
    const val ARTIFACT_UNZIP_FAILED = "ARTIFACT_UNZIP_FAILED"
    const val ARTIFACT_EXPANDED_TOO_LARGE = "ARTIFACT_EXPANDED_TOO_LARGE"
    const val ARTIFACT_TOO_MANY_IMAGES = "ARTIFACT_TOO_MANY_IMAGES"
    const val ARTIFACT_IMAGE_TOO_LARGE = "ARTIFACT_IMAGE_TOO_LARGE"
    const val ARTIFACT_NO_RELEASE_IMAGE = "ARTIFACT_NO_RELEASE_IMAGE"
    const val ARTIFACT_ZIP_INVALID = "ARTIFACT_ZIP_INVALID"
    const val ARTIFACT_ZIP_MULTI_DISK = "ARTIFACT_ZIP_MULTI_DISK"
    const val ARTIFACT_ZIP_DIRECTORY_CORRUPT = "ARTIFACT_ZIP_DIRECTORY_CORRUPT"
    const val ARTIFACT_ZIP_ENTRY_CORRUPT = "ARTIFACT_ZIP_ENTRY_CORRUPT"
    const val ARTIFACT_ZIP64_UNSUPPORTED = "ARTIFACT_ZIP64_UNSUPPORTED"
    const val ARTIFACT_ZIP_ENCRYPTED = "ARTIFACT_ZIP_ENCRYPTED"
    const val ARTIFACT_ZIP_UNSAFE_PATH = "ARTIFACT_ZIP_UNSAFE_PATH"
    const val ARTIFACT_ZIP_SYMLINK = "ARTIFACT_ZIP_SYMLINK"

    // ---- HTTP 接入（/platform/pulse/*，匿名，按 IP 限流） ----
    const val INGEST_APP_ID_MISSING = "INGEST_APP_ID_MISSING"
    const val INGEST_APP_ID_UNKNOWN = "INGEST_APP_ID_UNKNOWN"
    const val INGEST_APP_ID_DISABLED = "INGEST_APP_ID_DISABLED"
    const val INGEST_PACKAGE_NOT_REGISTERED = "INGEST_PACKAGE_NOT_REGISTERED"
    const val INGEST_PACKAGE_NOT_ALLOWED = "INGEST_PACKAGE_NOT_ALLOWED"
    const val INGEST_RATE_LIMITED = "INGEST_RATE_LIMITED"
    const val UPDATE_CHECK_RATE_LIMITED = "UPDATE_CHECK_RATE_LIMITED"
}

/**
 * 参数非法 → 400；给了 [field] 时附一条字段错误（[params] 是客户端渲染用的约束值，只随字段错误下发——
 * 信封本身没有参数位，不带字段的码在语言包里直接写死上限）。
 */
internal fun pulseInvalid(code: String, field: String? = null, params: Map<String, Long>? = null): Nothing =
    throw BadRequestException(code, if (field == null) emptyList() else listOf(ValidationError.of(field, code, params)))

/** 引用的应用 / 版本 / 问题不存在 → 404。 */
internal fun pulseNotFound(code: String): Nothing = throw NotFoundException(code)

/** 唯一性冲突（应用标识、构建号重复）→ 409。 */
internal fun pulseTaken(code: String, field: String): Nothing =
    throw HttpException(NetonErrorCode.RESOURCE_ALREADY_EXISTS, code, listOf(ValidationError.of(field, code)))
