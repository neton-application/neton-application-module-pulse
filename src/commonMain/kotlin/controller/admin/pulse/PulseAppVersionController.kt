package controller.admin.pulse

import controller.admin.pulse.dto.PublishRequest
import controller.admin.pulse.dto.SaveVersionRequest
import dto.PageResponse
import logic.PulseAppLogic
import model.PulseAppVersion
import neton.core.annotations.Body
import logic.PulseErrorCodes
import logic.pulseNotFound
import neton.core.annotations.Controller
import neton.core.annotations.Delete
import neton.core.annotations.Get
import neton.core.annotations.PathVariable
import neton.core.annotations.Permission
import neton.core.annotations.Post
import neton.core.annotations.Put
import neton.core.annotations.Query

/**
 * Release management for one application.
 *
 * Publishing is a separate endpoint and a separate permission from editing. Editing a draft is
 * harmless; publishing immediately changes what every client in the field is told at startup, and
 * publishing something marked forceUpdate locks users out of the app until they update. Those are
 * not the same authority.
 */
@Controller("/pulse/app-version")
class PulseAppVersionController(private val apps: PulseAppLogic) {

    @Get("/page")
    @Permission("pulse:app:list")
    suspend fun page(
        @Query appId: Long = 0,
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 20,
    ): PageResponse<PulseAppVersion> {
        val size = pageSize.coerceIn(1, 200); val no = pageNo.coerceAtLeast(1)
        if (appId <= 0) return PageResponse(emptyList(), 0, no, size, 0)
        val all = apps.versions(appId)
        val window = all.drop((no - 1) * size).take(size)
        val totalPages = if (all.isEmpty()) 0 else (all.size + size - 1) / size
        return PageResponse(window, all.size.toLong(), no, size, totalPages)
    }

    @Get("/get/{id}")
    @Permission("pulse:app:list")
    suspend fun get(@PathVariable id: Long): PulseAppVersion? = apps.getVersion(id)

    @Post("/create")
    @Permission("pulse:app:save")
    suspend fun create(@Body request: SaveVersionRequest): Long =
        apps.createVersion(request.toModel(publishedAt = if (request.published == 1) nowMs() else null))

    @Put("/update")
    @Permission("pulse:app:save")
    suspend fun update(@Body request: SaveVersionRequest) {
        val existing = apps.getVersion(request.id)
            ?: pulseNotFound(PulseErrorCodes.VERSION_NOT_FOUND)
        // The original publish time survives edits: fixing a typo in the notes is not a re-release.
        apps.updateVersion(request.toModel(publishedAt = existing.publishedAt))
    }

    @Put("/publish")
    @Permission("pulse:app:publish")
    suspend fun publish(@Body request: PublishRequest) =
        apps.setPublished(request.id, request.published, nowMs())

    @Delete("/delete/{id}")
    @Permission("pulse:app:delete")
    suspend fun delete(@PathVariable id: Long) = apps.deleteVersion(id)

    private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}
