package logic

import kotlinx.serialization.Serializable
import model.PulseApp
import model.PulseAppVersion
import model.PulsePlatform
import neton.core.http.BadRequestException
import neton.database.dsl.*
import neton.logging.Logger
import table.PulseAppTable
import table.PulseAppVersionTable

/**
 * What a client is told when it asks whether it should update.
 *
 * [UpdateAction] rather than two booleans: "no update", "offer an update" and "must update" are
 * three states, and a pair of flags allows a fourth that means nothing.
 */
@Serializable
data class UpdateCheckResult(
    val action: String,
    val latestVersionName: String? = null,
    val latestBuildNumber: Long? = null,
    val releaseNotes: String = "",
    val downloadUrl: String? = null,
)

object UpdateAction {
    /** Already current, or the app is unknown/disabled — either way, do not interrupt the user. */
    const val NONE = "none"
    /** A newer published build exists; the client may prompt and the user may decline. */
    const val OPTIONAL = "optional"
    /** A newer published build is marked forceUpdate; the client must block business features. */
    const val FORCED = "forced"
}

/**
 * Application and release management, plus the startup update check.
 *
 * The check is deliberately forgiving: an unknown app key, a disabled app or a platform we have no
 * release for all answer [UpdateAction.NONE]. A telemetry SDK must never be the reason an app
 * refuses to start, so anything other than "there is definitely a newer published build" resolves
 * to letting the client through.
 */
@neton.core.annotations.Logic(logger = "logic.pulse-app")
class PulseAppLogic(private val log: Logger) {

    // ---- apps

    suspend fun createApp(app: PulseApp): Long {
        validate(app)
        val inserted = PulseAppTable.insert(app)
        log.info("created pulse app id=${inserted.id} platform=${app.platform} key=${app.appKey}")
        return inserted.id
    }

    suspend fun updateApp(app: PulseApp) {
        validate(app)
        PulseAppTable.update(app)
        log.info("updated pulse app id=${app.id}")
    }

    /**
     * Removing an app takes its releases with it. They are meaningless on their own, and leaving
     * them behind would let a later app reusing the same id inherit someone else's release history.
     */
    suspend fun deleteApp(id: Long) {
        for (v in versions(id)) PulseAppVersionTable.destroy(v.id)
        PulseAppTable.destroy(id)
        log.info("deleted pulse app id=$id and its versions")
    }

    suspend fun getApp(id: Long): PulseApp? = PulseAppTable.get(id)

    suspend fun listApps(platform: String? = null, keyword: String? = null): List<PulseApp> =
        PulseAppTable.query {
            where {
                and(
                    whenPresent(platform?.takeIf { it.isNotBlank() }) { PulseApp::platform eq it },
                    whenPresent(keyword?.takeIf { it.isNotBlank() }) { PulseApp::name like "%$it%" },
                )
            }
            orderBy(PulseApp::id.desc())
        }.list()

    /**
     * BadRequestException rather than require(): an IllegalArgumentException surfaces to the
     * console as "Internal Server Error", which tells the operator nothing. These are all things
     * the person filling in the form can fix, so they have to come back as 4xx with the reason.
     */
    private suspend fun validate(app: PulseApp) {
        if (app.name.isBlank()) throw BadRequestException("应用名称不能为空")
        if (app.appKey.isBlank()) throw BadRequestException("应用标识不能为空")
        if (!PulsePlatform.isValid(app.platform)) {
            throw BadRequestException("未知平台 '${app.platform}'，可选：${PulsePlatform.ALL.joinToString("、")}")
        }
        // Web has no package name; every native platform must have one, since it is how a build in
        // the field is identified when it does not know its own app key.
        if (app.platform != PulsePlatform.WEB && app.packageName.isNullOrBlank()) {
            throw BadRequestException("平台 '${app.platform}' 必须填写包名")
        }
        // Checked here rather than left to the unique index, so the operator gets a sentence
        // instead of a constraint violation. The index still backstops a race.
        val clash = PulseAppTable.query {
            where { and(PulseApp::platform eq app.platform, PulseApp::appKey eq app.appKey) }
        }.list().firstOrNull { it.id != app.id }
        if (clash != null) {
            throw BadRequestException("平台 '${app.platform}' 下已存在应用标识 '${app.appKey}'（${clash.name}）")
        }
    }

    // ---- versions

    suspend fun createVersion(version: PulseAppVersion): Long {
        validate(version)
        val inserted = PulseAppVersionTable.insert(version)
        log.info("created version id=${inserted.id} app=${version.appId} build=${version.buildNumber}")
        return inserted.id
    }

    suspend fun updateVersion(version: PulseAppVersion) {
        validate(version)
        PulseAppVersionTable.update(version)
        log.info("updated version id=${version.id}")
    }

    suspend fun deleteVersion(id: Long) {
        PulseAppVersionTable.destroy(id)
        log.info("deleted version id=$id")
    }

    suspend fun getVersion(id: Long): PulseAppVersion? = PulseAppVersionTable.get(id)

    suspend fun versions(appId: Long): List<PulseAppVersion> =
        PulseAppVersionTable.query {
            where { PulseAppVersion::appId eq appId }
            orderBy(PulseAppVersion::buildNumber.desc())
        }.list()

    /** Publishing stamps the time, so "when did this go out" does not depend on an edit trail. */
    suspend fun setPublished(id: Long, published: Boolean, nowMs: Long) {
        val existing = PulseAppVersionTable.get(id) ?: throw BadRequestException("版本不存在：$id")
        PulseAppVersionTable.update(
            existing.copy(
                published = if (published) 1 else 0,
                publishedAt = if (published) (existing.publishedAt ?: nowMs) else null,
            ),
        )
        log.info("version id=$id published=$published")
    }

    private suspend fun validate(version: PulseAppVersion) {
        if (version.versionName.isBlank()) throw BadRequestException("版本名不能为空")
        if (version.buildNumber <= 0) throw BadRequestException("构建号必须为正整数")
        if (PulseAppTable.get(version.appId) == null) throw BadRequestException("应用不存在：${version.appId}")
        // Same reasoning as the app key: a duplicate build number would make "which is latest"
        // ambiguous, and the operator should be told that in words.
        val clash = PulseAppVersionTable.query {
            where { and(PulseAppVersion::appId eq version.appId, PulseAppVersion::buildNumber eq version.buildNumber) }
        }.list().firstOrNull { it.id != version.id }
        if (clash != null) {
            throw BadRequestException("该应用下构建号 ${version.buildNumber} 已存在（${clash.versionName}）")
        }
    }

    // ---- update check

    /**
     * Resolve the app by [appKey] first, falling back to [packageName]: a build in the field always
     * knows its own bundle id, while the app key is SDK configuration that an older build might not
     * be sending yet.
     */
    suspend fun checkUpdate(
        platform: String,
        appKey: String?,
        packageName: String?,
        buildNumber: Long,
    ): UpdateCheckResult {
        val app = findApp(platform, appKey, packageName)
        if (app == null || app.status != 1) return UpdateCheckResult(UpdateAction.NONE)

        val latest = PulseAppVersionTable.query {
            where {
                and(
                    PulseAppVersion::appId eq app.id,
                    PulseAppVersion::published eq 1,
                )
            }
            orderBy(PulseAppVersion::buildNumber.desc())
            limitOffset(1, 0)
        }.list().firstOrNull() ?: return UpdateCheckResult(UpdateAction.NONE)

        if (latest.buildNumber <= buildNumber) return UpdateCheckResult(UpdateAction.NONE)

        return UpdateCheckResult(
            action = if (latest.forceUpdate) UpdateAction.FORCED else UpdateAction.OPTIONAL,
            latestVersionName = latest.versionName,
            latestBuildNumber = latest.buildNumber,
            releaseNotes = latest.releaseNotes,
            downloadUrl = latest.downloadUrl,
        )
    }

    private suspend fun findApp(platform: String, appKey: String?, packageName: String?): PulseApp? {
        appKey?.takeIf { it.isNotBlank() }?.let { key ->
            PulseAppTable.query {
                where { and(PulseApp::platform eq platform, PulseApp::appKey eq key) }
            }.list().firstOrNull()?.let { return it }
        }
        packageName?.takeIf { it.isNotBlank() }?.let { pkg ->
            return PulseAppTable.query {
                where { and(PulseApp::platform eq platform, PulseApp::packageName eq pkg) }
            }.list().firstOrNull()
        }
        return null
    }
}
