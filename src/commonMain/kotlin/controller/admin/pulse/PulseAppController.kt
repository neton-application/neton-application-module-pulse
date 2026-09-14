package controller.admin.pulse

import controller.admin.pulse.dto.AppRow
import controller.admin.pulse.dto.SaveAppRequest
import dto.PageResponse
import logic.PulseAppLogic
import model.PulseApp
import model.PulsePlatform
import neton.core.annotations.Body
import neton.core.http.BadRequestException
import neton.core.annotations.Controller
import neton.core.annotations.Delete
import neton.core.annotations.Get
import neton.core.annotations.PathVariable
import neton.core.annotations.Permission
import neton.core.annotations.Post
import neton.core.annotations.Put
import neton.core.annotations.Query

/**
 * Application management. One record per platform, so the console lists e.g. "积分商城 iOS" and
 * "积分商城 Android" separately; each carries its own releases and its own force-update decisions.
 */
@Controller("/pulse/app")
class PulseAppController(private val apps: PulseAppLogic) {

    /** The platform vocabulary, so the console's select does not keep its own copy of it. */
    @Get("/platforms")
    @Permission("pulse:app:list")
    suspend fun platforms(): List<String> = PulsePlatform.ALL

    @Get("/page")
    @Permission("pulse:app:list")
    suspend fun page(
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 20,
        @Query platform: String? = null,
        @Query name: String? = null,
    ): PageResponse<AppRow> {
        val size = pageSize.coerceIn(1, 200); val no = pageNo.coerceAtLeast(1)
        val all = apps.listApps(platform, name)
        val window = all.drop((no - 1) * size).take(size)
        val rows = window.map { app ->
            val versions = apps.versions(app.id)
            val live = versions.firstOrNull { it.published == 1 }
            AppRow(
                id = app.id, name = app.name, platform = app.platform, appKey = app.appKey,
                packageName = app.packageName, packagePolicy = app.packagePolicy,
                allowedPackages = app.allowedPackages, remark = app.remark, status = app.status,
                createdAt = app.createdAt,
                versionCount = versions.size,
                liveVersion = live?.versionName, liveBuild = live?.buildNumber,
                liveForced = live?.forceUpdate ?: false,
            )
        }
        val totalPages = if (all.isEmpty()) 0 else (all.size + size - 1) / size
        return PageResponse(rows, all.size.toLong(), no, size, totalPages)
    }

    @Get("/get/{id}")
    @Permission("pulse:app:list")
    suspend fun get(@PathVariable id: Long): PulseApp? = apps.getApp(id)

    @Post("/create")
    @Permission("pulse:app:save")
    suspend fun create(@Body request: SaveAppRequest): Long = apps.createApp(request.toModel())

    @Put("/update")
    @Permission("pulse:app:save")
    suspend fun update(@Body request: SaveAppRequest) {
        val existing = apps.getApp(request.id) ?: throw BadRequestException("应用不存在：${request.id}")
        apps.updateApp(request.toModel(existing.appKey))
    }

    @Delete("/delete/{id}")
    @Permission("pulse:app:delete")
    suspend fun delete(@PathVariable id: Long) = apps.deleteApp(id)
}
