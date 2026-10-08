package model

import kotlinx.serialization.Serializable
import neton.database.annotations.CreatedAt
import neton.database.annotations.Id
import neton.database.annotations.Table
import neton.database.annotations.UpdatedAt

/**
 * One distinct crash, aggregated across every occurrence.
 *
 * The raw events stay in pulse_events — those are the facts. This is the derived view a developer
 * actually works from: the same fault on ten thousand devices is one issue with a device count,
 * not ten thousand rows to scroll past.
 */
@Serializable
@Table("pulse_crash_issues")
data class PulseCrashIssue(
    @Id val id: Long = 0,
    val projectId: String = "",
    /** Grouping key; see [logic.CrashFingerprint]. */
    val fingerprint: String,
    val title: String = "",
    /** 事件类别:`Crash`(致命)或 `Error`(已捕获)。两者归并方式相同,优先级不同。 */
    val kind: String = "Crash",
    val message: String = "",
    /**
     * The frames from the first occurrence, raw. Symbolication happens on read, not here: symbols
     * arrive when someone uploads them, which can be after the crash, and a stored symbol goes
     * stale while a raw address never does.
     */
    val sampleFrames: String = "",
    val sampleSlide: Long = 0,
    /**
     * 代表性**文本**堆栈（Android Java/Kotlin 崩溃上报的 `stack`）。iOS 原生崩溃没有它（走
     * 数值帧 [sampleFrames] + 符号表），Android 没有数值帧、只有这份文本栈。读详情时若该构建
     * 有上传的 R8 mapping，就对它做反混淆。和符号一样不落反混淆结果:mapping 会补传/更正。
     */
    val sampleStack: String = "",
    val appKey: String? = null,
    val buildNumber: Long? = null,
    val firstSeenAt: Long = 0,
    val lastSeenAt: Long = 0,
    val eventCount: Long = 0,
    val deviceCount: Long = 0,
    /** [CrashStatus]. */
    val status: Int = CrashStatus.OPEN,
    @CreatedAt val createdAt: Long? = null,
    @UpdatedAt val updatedAt: Long? = null,
)

object CrashStatus {
    const val OPEN = 0
    const val IN_PROGRESS = 1
    const val RESOLVED = 2
    const val IGNORED = 3
}

/** One device that hit one issue; the basis for an honest device count. */
@Serializable
@Table("pulse_crash_sightings")
data class PulseCrashSighting(
    @Id val id: Long = 0,
    val issueId: Long,
    val deviceId: String = "",
    val buildNumber: Long? = null,
    val firstSeenAt: Long = 0,
    val lastSeenAt: Long = 0,
)

/**
 * One symbol's starting address within a build.
 *
 * Start addresses only, not ranges: resolving an address is "the greatest symbol address at or
 * below it", which needs no end and stays correct when a build's symbols are uploaded partially.
 */
/**
 * 一个构建的 R8/ProGuard mapping，整份文本按 (appKey, buildNumber) 存。
 *
 * Android 的 Java/Kotlin 崩溃栈是混淆后的类/方法名,只有这份 mapping 能把它还原成可读代码。
 * 和 [PulseSymbol] 一样按构建归属:同一份混淆名只在生成它的那个构建里成立。
 */
@Serializable
@Table("pulse_android_mappings")
data class PulseAndroidMapping(
    @Id val id: Long = 0,
    val appKey: String,
    val buildNumber: Long,
    /** 原始 mapping.txt 全文。 */
    val mapping: String,
    @CreatedAt val createdAt: Long? = null,
)

@Serializable
@Table("pulse_symbols")
data class PulseSymbol(
    @Id val id: Long = 0,
    val appKey: String,
    val buildNumber: Long,
    /** Static address, independent of where the image happened to load. */
    val address: Long,
    val symbol: String,
)
