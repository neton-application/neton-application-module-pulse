package controller.admin.pulse

import logic.PulseArtifactLogic
import logic.PulseArtifactReport
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.Permission
import neton.core.annotations.Post
import neton.core.annotations.Query
import logic.PulseErrorCodes
import logic.pulseInvalid
import neton.core.http.HttpRequest

@Controller("/pulse/artifact")
class PulseArtifactController(private val artifacts: PulseArtifactLogic) {

    @Post("/analyze")
    @Permission("pulse:app:save")
    suspend fun analyze(request: HttpRequest): PulseArtifactReport {
        val (versionId, fileName, bytes) = if (request.isMultipart()) {
            // Kept for API clients. The console uses the raw path below because Neton's current
            // buffered multipart parser copies a large part several times.
            val versionId = request.form()["versionId"]?.toLongOrNull()
                ?: pulseInvalid(PulseErrorCodes.VERSION_ID_REQUIRED, "versionId")
            val file = request.uploadFiles().first("artifact")
                ?: pulseInvalid(PulseErrorCodes.ARTIFACT_FILE_REQUIRED, "artifact")
            if (file.size <= 0L || file.size > MAX_ARTIFACT_BYTES) sizeError()
            Triple(versionId, file.filename, file.bytes())
        } else {
            val versionId = request.queryParam("versionId")?.toLongOrNull()
                ?: pulseInvalid(PulseErrorCodes.VERSION_ID_REQUIRED, "versionId")
            val fileName = request.queryParam("fileName")?.takeIf(String::isNotBlank)
                ?: pulseInvalid(PulseErrorCodes.ARTIFACT_FILE_NAME_REQUIRED, "fileName")
            request.contentLength?.let { if (it <= 0L || it > MAX_ARTIFACT_BYTES) sizeError() }
            Triple(versionId, fileName, request.body())
        }
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_ARTIFACT_BYTES) sizeError()
        return artifacts.analyze(versionId, fileName, bytes)
    }

    @Get("/get")
    @Permission("pulse:app:list")
    suspend fun get(@Query versionId: Long): PulseArtifactReport? = artifacts.get(versionId)

    private companion object {
        const val MAX_ARTIFACT_BYTES = 512L * 1024 * 1024
    }

    private fun sizeError(): Nothing = pulseInvalid(PulseErrorCodes.ARTIFACT_SIZE_INVALID)
}
