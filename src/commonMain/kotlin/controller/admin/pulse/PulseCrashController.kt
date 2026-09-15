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

    @Get("/page")
    @Permission("pulse:crash:list")
    suspend fun page(
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 20,
        @Query status: Int? = null,
        @Query projectId: String? = null,
        @Query days: Int? = null,
    ): PageResponse<PulseCrashIssue> {
        val size = pageSize.coerceIn(1, 200); val no = pageNo.coerceAtLeast(1)
        val since = days?.coerceIn(1, 30)?.let {
            kotlin.time.Clock.System.now().toEpochMilliseconds() - it * 86_400_000L
        }
        val all = crashes.listIssues(status, projectId, since)
        val window = all.drop((no - 1) * size).take(size)
        val pages = if (all.isEmpty()) 0 else (all.size + size - 1) / size
        return PageResponse(window, all.size.toLong(), no, size, pages)
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
}
