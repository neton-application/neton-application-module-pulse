package logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import model.CrashStatus
import model.PulseCrashIssue
import model.PulseCrashSighting
import model.PulseEvent
import model.PulseSymbol
import neton.core.http.BadRequestException
import neton.database.dsl.*
import neton.logging.Logger
import table.PulseCrashIssueTable
import table.PulseCrashSightingTable
import table.PulseSymbolTable

/** One resolved stack frame, as the console shows it. */
@Serializable
data class SymbolicatedFrame(
    val index: Int,
    /** Address as the crashing process saw it. */
    val runtimeAddress: Long,
    /** Address within the build, after removing the load slide. */
    val staticAddress: Long,
    /** Resolved function, or null when no symbol covers this address. */
    val symbol: String? = null,
    /** Bytes past the symbol's start; the line-level hint you get without full debug info. */
    val offset: Long? = null,
)

/** An issue plus its stack, resolved as far as the uploaded symbols allow. */
@Serializable
data class CrashIssueDetail(
    val issue: PulseCrashIssue,
    val frames: List<SymbolicatedFrame>,
    /** False when nothing resolved, so the console can say why rather than showing bare numbers. */
    val symbolicated: Boolean,
)

/**
 * Crash aggregation and stack symbolication.
 *
 * Aggregation groups occurrences into issues by fingerprint; see [CrashFingerprint] for why the
 * key is the top frames' static addresses rather than the message text.
 *
 * Symbolication happens on read, never at write time. Symbols usually arrive after the crashes do
 * — someone uploads them when the build is released, or later when they need to read a report —
 * and a stored symbol would be wrong the moment a better table is uploaded. A raw address stays
 * valid forever.
 */
@neton.core.annotations.Logic(logger = "logic.pulse-crash")
class PulseCrashLogic(
    private val db: neton.database.api.DbContext,
    private val log: Logger,
) {

    // ---- aggregation, called from the ingest path

    suspend fun aggregate(rows: List<PulseEvent>, nowMs: Long) {
        val crashes = rows.filter { it.kind == "Crash" || it.kind == "Error" }
        for (row in crashes) {
            val attrs = attributes(row.attributes)
            val message = attrs["message"] ?: ""
            val frames = CrashFingerprint.parseFrames(attrs["frames"])
            val slide = attrs["image_slide"]?.toLongOrNull() ?: 0L
            val fingerprint = CrashFingerprint.of(row.name, message, frames, slide)
            record(row, fingerprint, message, attrs["frames"] ?: "", slide, nowMs)
        }
    }

    private suspend fun record(
        row: PulseEvent, fingerprint: String, message: String,
        rawFrames: String, slide: Long, nowMs: Long,
    ) {
        val projectId = row.projectId ?: ""
        val existing = PulseCrashIssueTable.query {
            where { and(PulseCrashIssue::projectId eq projectId, PulseCrashIssue::fingerprint eq fingerprint) }
        }.list().firstOrNull()

        val issueId: Long
        if (existing == null) {
            issueId = PulseCrashIssueTable.insert(
                PulseCrashIssue(
                    projectId = projectId, fingerprint = fingerprint,
                    title = row.name, message = message.take(500),
                    sampleFrames = rawFrames, sampleSlide = slide,
                    appKey = row.projectId, buildNumber = row.buildNumber,
                    firstSeenAt = row.ts.takeIf { it > 0 } ?: nowMs,
                    lastSeenAt = row.ts.takeIf { it > 0 } ?: nowMs,
                    eventCount = 1, deviceCount = 0, status = CrashStatus.OPEN,
                ),
            ).id
            log.info("pulse crash issue opened: $fingerprint (${row.name}) project=$projectId")
        } else {
            issueId = existing.id
            // A resolved issue that happens again is not resolved. Reopening automatically is the
            // whole point of tracking status against a fingerprint rather than against a build.
            val status = if (existing.status == CrashStatus.RESOLVED) CrashStatus.OPEN else existing.status
            PulseCrashIssueTable.update(
                existing.copy(
                    lastSeenAt = maxOf(existing.lastSeenAt, row.ts.takeIf { it > 0 } ?: nowMs),
                    eventCount = existing.eventCount + 1,
                    status = status,
                    // Keep the first sample: it is the one whose slide and frames belong together.
                    sampleFrames = existing.sampleFrames.ifEmpty { rawFrames },
                    sampleSlide = if (existing.sampleFrames.isEmpty()) slide else existing.sampleSlide,
                    // Keep the build the sample came from, so the stack resolves against the right
                    // symbol table even after newer builds start reporting the same issue.
                    buildNumber = existing.buildNumber ?: row.buildNumber,
                ),
            )
            if (status != existing.status) log.info("pulse crash issue reopened: $fingerprint")
        }

        val deviceId = row.deviceId ?: return
        val seen = PulseCrashSightingTable.query {
            where { and(PulseCrashSighting::issueId eq issueId, PulseCrashSighting::deviceId eq deviceId) }
        }.list().firstOrNull()
        if (seen == null) {
            PulseCrashSightingTable.insert(
                PulseCrashSighting(
                issueId = issueId, deviceId = deviceId, buildNumber = row.buildNumber,
                firstSeenAt = nowMs, lastSeenAt = nowMs,
            ),
            )
            // Recounted rather than incremented: the ingest path is at-least-once, and an
            // incremented counter would drift upward on every replayed batch.
            val count = PulseCrashSightingTable.query { where { PulseCrashSighting::issueId eq issueId } }.count()
            PulseCrashIssueTable.get(issueId)?.let { PulseCrashIssueTable.update(it.copy(deviceCount = count)) }
        } else {
            PulseCrashSightingTable.update(seen.copy(lastSeenAt = nowMs))
        }
    }

    // ---- reading

    /** Most impactful first: devices affected, then recency. Not raw event count — one device */
    /** crashing in a loop is not more important than a thousand devices crashing once. */
    suspend fun listIssues(status: Int? = null): List<PulseCrashIssue> =
        PulseCrashIssueTable.query {
            where { whenPresent(status) { PulseCrashIssue::status eq it } }
        }.list().sortedWith(
            compareByDescending<PulseCrashIssue> { it.deviceCount }.thenByDescending { it.lastSeenAt },
        )

    suspend fun getIssue(id: Long): CrashIssueDetail? {
        val issue = PulseCrashIssueTable.get(id) ?: return null
        val frames = symbolicate(
            CrashFingerprint.parseFrames(issue.sampleFrames),
            issue.sampleSlide,
            issue.appKey,
            issue.buildNumber,
        )
        return CrashIssueDetail(issue, frames, frames.any { it.symbol != null })
    }

    suspend fun setStatus(id: Long, status: Int) {
        val issue = PulseCrashIssueTable.get(id) ?: throw BadRequestException("崩溃问题不存在：$id")
        PulseCrashIssueTable.update(issue.copy(status = status))
    }

    suspend fun devicesFor(issueId: Long, limit: Int = 100): List<PulseCrashSighting> =
        PulseCrashSightingTable.query {
            where { PulseCrashSighting::issueId eq issueId }
            orderBy(PulseCrashSighting::lastSeenAt.desc())
            limitOffset(limit, 0)
        }.list()

    /**
     * Resolve each frame against the uploaded symbols for its build.
     *
     * A frame with no symbol is returned with its addresses and a null name rather than dropped:
     * a partially resolved stack is far more useful than none, and the gaps themselves say which
     * image is missing symbols.
     */
    suspend fun symbolicate(frames: List<Long>, slide: Long, appKey: String?, buildNumber: Long?): List<SymbolicatedFrame> {
        val symbols = if (appKey != null && buildNumber != null) symbolsFor(appKey, buildNumber) else emptyList()
        val lowest = symbols.firstOrNull()?.address
        return frames.mapIndexed { i, runtime ->
            val static = runtime - slide
            // A crash stack walks through several images — the app, the Kotlin runtime, system
            // frameworks — and this table describes exactly one of them. A frame from another
            // image would otherwise match the last symbol below it and be reported as that
            // function with an absurd offset: a plausible-looking name pointing at the wrong code,
            // which is worse than an unresolved address because it sends someone to the wrong file.
            val candidate = if (lowest != null && static >= lowest) symbols.lastOrNull { it.address <= static } else null
            val offset = candidate?.let { static - it.address }
            val plausible = offset != null && offset <= MAX_SYMBOL_SPAN
            SymbolicatedFrame(
                index = i,
                runtimeAddress = runtime,
                staticAddress = static,
                symbol = if (plausible) candidate?.symbol else null,
                offset = if (plausible) offset else null,
            )
        }
    }

    private suspend fun symbolsFor(appKey: String, buildNumber: Long): List<PulseSymbol> =
        PulseSymbolTable.query {
            where { and(PulseSymbol::appKey eq appKey, PulseSymbol::buildNumber eq buildNumber) }
            orderBy(PulseSymbol::address.asc())
        }.list()

    // ---- symbol upload

    /**
     * Load a symbol table for one build from `nm -n` style text: an address in hex, a type letter,
     * and a name per line. Everything else on the line is ignored, so `nm`, `atos -o` listings and
     * hand-written maps all work.
     *
     * Full dSYM parsing is deliberately out of scope — that is a Mach-O and DWARF problem of a
     * different order, and start addresses already turn an address into a function name.
     */
    suspend fun uploadSymbols(appKey: String, buildNumber: Long, text: String): Int {
        if (appKey.isBlank()) throw BadRequestException("应用标识不能为空")
        if (buildNumber <= 0) throw BadRequestException("构建号必须为正整数")

        val parsed = SymbolTable.parse(text)
        if (parsed.isEmpty()) throw BadRequestException("没有解析出任何符号，请确认是 `nm -n` 的输出")

        // Replace rather than merge: a re-upload means the previous table was wrong or partial,
        // and mixing two tables would resolve some addresses against a build that no longer exists.
        db.execute(
            "DELETE FROM pulse_symbols WHERE app_key = :app_key AND build_number = :build_number",
            mapOf("app_key" to appKey, "build_number" to buildNumber),
        )

        // Multi-row inserts in chunks. A real symbol table is tens of thousands of rows, and one
        // statement per symbol would be tens of thousands of round trips — slow enough that the
        // upload times out before it finishes, which is how this was first written and why it is
        // not written that way now.
        for (chunk in parsed.chunked(SYMBOL_INSERT_CHUNK)) {
            val params = HashMap<String, Any?>(chunk.size * 4)
            val values = chunk.mapIndexed { i, (address, symbol) ->
                params["app_key_$i"] = appKey
                params["build_$i"] = buildNumber
                params["addr_$i"] = address
                params["sym_$i"] = symbol.take(512)
                "(:app_key_$i, :build_$i, :addr_$i, :sym_$i)"
            }.joinToString(", ")
            db.execute("INSERT INTO pulse_symbols (app_key, build_number, address, symbol) VALUES $values", params)
        }
        log.info("pulse symbols loaded: $appKey build=$buildNumber count=${parsed.size}")
        return parsed.size
    }

    suspend fun symbolCount(appKey: String, buildNumber: Long): Int = symbolsFor(appKey, buildNumber).size

    private fun attributes(raw: String): Map<String, String> = try {
        json.decodeFromString(JsonObject.serializer(), raw)
            .mapValues { it.value.jsonPrimitive.content }
    } catch (_: Throwable) {
        emptyMap()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** Rows per INSERT. Large enough to keep round trips down, small enough that the
         *  statement and its bind parameters stay within what the driver will accept. */
        const val SYMBOL_INSERT_CHUNK = 500

        /**
         * Largest believable distance from a symbol's start to an address inside it. No real
         * function is a megabyte of code, so a larger gap means the address belongs to an image
         * this table does not describe, and the match is a coincidence of ordering.
         */
        const val MAX_SYMBOL_SPAN = 1L shl 20
    }
}
