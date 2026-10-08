package controller.admin.pulse

import dto.PageResponse
import kotlinx.serialization.Serializable
import logic.CrashIssueDetail
import logic.PulseCrashLogic
import model.PulseCrashIssue
import model.PulseCrashSighting
import neton.core.annotations.Body
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.PathVariable
import neton.core.annotations.Permission
import neton.core.annotations.Post
import neton.core.annotations.Put
import neton.core.annotations.Query

/**
 * Crash issues: the aggregated view of what is actually breaking, and the stacks behind it.
 *
 * The raw occurrences stay available under /pulse/apm; this is the list a developer works from.
 */
@Controller("/pulse/crash")
class PulseCrashController(private val crashes: PulseCrashLogic) {

    @Serializable
    data class StatusRequest(val id: Long, val status: Int)

    @Serializable
    data class UploadSymbolsRequest(
        val appKey: String,
        val buildNumber: Long,
        /** `nm -n` output, or any "address name" listing. */
        val text: String,
    )

    @Serializable
    data class UploadSymbolsResponse(val loaded: Int)

    @Serializable
    data class UploadMappingRequest(
        val appKey: String,
        val buildNumber: Long,
        /** R8 / ProGuard mapping.txt 全文。 */
        val text: String,
    )

    @Serializable
    data class UploadMappingResponse(val classes: Int)

    /** Counts for the whole filtered scope, so the list header does not have to lie about a page. */
    @Serializable
    data class Summary(
        val total: Long, val open: Long, val inProgress: Long, val resolved: Long, val ignored: Long,
        val crash: Long, val error: Long, val devices: Long,
    )

    @Get("/page")
    @Permission("pulse:crash:list")
    suspend fun page(
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 20,
        @Query status: Int? = null,
        @Query projectId: String? = null,
        @Query days: Int? = null,
        @Query kind: String? = null,
    ): PageResponse<PulseCrashIssue> {
        val size = pageSize.coerceIn(1, 200); val no = pageNo.coerceAtLeast(1)
        val all = crashes.listIssues(status, projectId, sinceOf(days), kind)
        val window = all.drop((no - 1) * size).take(size)
        val pages = if (all.isEmpty()) 0 else (all.size + size - 1) / size
        return PageResponse(window, all.size.toLong(), no, size, pages)
    }

    /**
     * Everything the header states about the current scope. Derived from the same query the list
     * runs, but before paging: a count taken from one page would change as the operator turns pages.
     */
    @Get("/summary")
    @Permission("pulse:crash:list")
    suspend fun summary(
        @Query projectId: String? = null,
        @Query days: Int? = null,
    ): Summary {
        val all = crashes.listIssues(null, projectId, sinceOf(days), null)
        return Summary(
            total = all.size.toLong(),
            open = all.count { it.status == model.CrashStatus.OPEN }.toLong(),
            inProgress = all.count { it.status == model.CrashStatus.IN_PROGRESS }.toLong(),
            resolved = all.count { it.status == model.CrashStatus.RESOLVED }.toLong(),
            ignored = all.count { it.status == model.CrashStatus.IGNORED }.toLong(),
            crash = all.count { it.kind == "Crash" }.toLong(),
            error = all.count { it.kind == "Error" }.toLong(),
            // Sums of per-issue device counts overlap; this is "how many device-issue pairs hurt",
            // which is the number a triage queue is actually ranked by.
            devices = all.sumOf { it.deviceCount },
        )
    }

    private fun sinceOf(days: Int?): Long? = days?.coerceIn(1, 30)?.let {
        kotlin.time.Clock.System.now().toEpochMilliseconds() - it * 86_400_000L
    }

    /** The issue with its stack resolved as far as the uploaded symbols allow. */
    @Get("/get/{id}")
    @Permission("pulse:crash:list")
    suspend fun get(@PathVariable id: Long): CrashIssueDetail? = crashes.getIssue(id)

    @Get("/devices/{id}")
    @Permission("pulse:crash:list")
    suspend fun devices(@PathVariable id: Long, @Query limit: Int = 100): List<PulseCrashSighting> =
        crashes.devicesFor(id, limit.coerceIn(1, 500))

    @Put("/status")
    @Permission("pulse:crash:triage")
    suspend fun setStatus(@Body request: StatusRequest) = crashes.setStatus(request.id, request.status)

    /**
     * Load the symbol table for one build.
     *
     * Its own permission: symbols are what turn addresses into function names, so uploading a
     * wrong table makes every stack for that build quietly misleading rather than merely
     * unreadable.
     */
    @Post("/symbols/upload")
    @Permission("pulse:crash:symbols")
    suspend fun uploadSymbols(@Body request: UploadSymbolsRequest): UploadSymbolsResponse =
        UploadSymbolsResponse(crashes.uploadSymbols(request.appKey, request.buildNumber, request.text))

    @Get("/symbols/count")
    @Permission("pulse:crash:list")
    suspend fun symbolCount(@Query appKey: String, @Query buildNumber: Long): Int =
        crashes.symbolCount(appKey, buildNumber)

    /**
     * Load the R8 mapping for one Android build.
     *
     * Same shape and reasoning as [uploadSymbols], for the other platform: Android crashes carry a
     * textual stack whose class and method names are obfuscated, and only this build's mapping turns
     * them back into readable code. Its own permission for the same reason — a wrong mapping makes
     * every stack for that build plausibly-but-wrongly named.
     */
    @Post("/mapping/upload")
    @Permission("pulse:crash:symbols")
    suspend fun uploadMapping(@Body request: UploadMappingRequest): UploadMappingResponse =
        UploadMappingResponse(crashes.uploadMapping(request.appKey, request.buildNumber, request.text))
}
