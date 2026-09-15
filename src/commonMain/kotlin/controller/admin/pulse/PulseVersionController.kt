package controller.admin.pulse

import logic.PulseVersionLogic
import logic.VersionComparison
import logic.VersionStats
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.Permission
import neton.core.annotations.Query

/**
 * Version-dimension reporting: how each build is doing, and what a release changed.
 *
 * Reuses the stats permission rather than inventing one — this is the same question the overview
 * answers, asked per build.
 */
@Controller("/pulse/version")
class PulseVersionController(private val versions: PulseVersionLogic) {

    @Get("/list")
    @Permission("pulse:stats:view")
    suspend fun list(
        @Query projectId: String? = null,
        @Query days: Int? = null,
    ): List<VersionStats> = versions.builds(projectId, days)

    /**
     * One build against another. [baselineBuild] defaults to the previous build that actually
     * reported data, which is almost always what someone means by "compared to the last version".
     */
    @Get("/compare")
    @Permission("pulse:stats:view")
    suspend fun compare(
        @Query buildNumber: Long,
        @Query baselineBuild: Long? = null,
        @Query projectId: String? = null,
        @Query days: Int? = null,
    ): VersionComparison = versions.compare(projectId, buildNumber, baselineBuild, days)
}
