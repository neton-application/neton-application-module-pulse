package logic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.PulsePlatform
import neton.core.http.BadRequestException
import neton.database.api.DbContext
import neton.logging.Logger
import table.PulseAppTable
import table.PulseAppVersionTable

@Serializable
data class PulseArtifactReport(
    val id: Long,
    val versionId: Long,
    val fileName: String,
    val fileSize: Long,
    val archiveSha256: String,
    val format: String,
    val imageCount: Int,
    val swiftSymbolCount: Int,
    val cppSymbolCount: Int,
    val cSymbolCount: Int,
    val importedSymbolCount: Int,
    val signedImageCount: Int,
    val createdAt: Long,
    val images: List<IosImageAnalysis>,
)

/**
 * Final-artifact evidence for one managed iOS release.
 *
 * Analysis runs outside the HTTP reactor: ZIP decoding, hashing a large __TEXT segment and walking
 * symbol tables are blocking/CPU work. Persistence is replace-in-one-transaction so a re-upload
 * cannot expose the new summary together with old image evidence.
 */
@neton.core.annotations.Logic(logger = "logic.pulse-artifact")
class PulseArtifactLogic(
    private val db: DbContext,
    private val log: Logger,
) {
    suspend fun analyze(versionId: Long, fileName: String, bytes: ByteArray): PulseArtifactReport {
        val version = PulseAppVersionTable.get(versionId)
            ?: throw BadRequestException("版本不存在：$versionId")
        val app = PulseAppTable.get(version.appId)
            ?: throw BadRequestException("版本所属应用不存在：${version.appId}")
        if (app.platform != PulsePlatform.IOS) throw BadRequestException("只有 iOS 应用版本可上传 IPA/XCArchive")

        val result = withContext(Dispatchers.Default) { IosArtifactAnalyzer.analyze(fileName, bytes) }
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val reportId = db.transaction {
            val row = fetchAll(
                """
                INSERT INTO pulse_artifact_analyses (
                    version_id, file_name, file_size, archive_sha256, artifact_format, image_count,
                    swift_symbol_count, cpp_symbol_count, c_symbol_count, imported_symbol_count,
                    signed_image_count, created_at
                ) VALUES (
                    :version_id, :file_name, :file_size, :archive_sha256, :format, :image_count,
                    :swift_count, :cpp_count, :c_count, :import_count, :signed_count, :created_at
                )
                ON CONFLICT (version_id) DO UPDATE SET
                    file_name = EXCLUDED.file_name, file_size = EXCLUDED.file_size,
                    archive_sha256 = EXCLUDED.archive_sha256, artifact_format = EXCLUDED.artifact_format,
                    image_count = EXCLUDED.image_count, swift_symbol_count = EXCLUDED.swift_symbol_count,
                    cpp_symbol_count = EXCLUDED.cpp_symbol_count, c_symbol_count = EXCLUDED.c_symbol_count,
                    imported_symbol_count = EXCLUDED.imported_symbol_count,
                    signed_image_count = EXCLUDED.signed_image_count, created_at = EXCLUDED.created_at
                RETURNING id
                """.trimIndent(),
                mapOf(
                    "version_id" to versionId, "file_name" to fileName.take(255), "file_size" to bytes.size.toLong(),
                    "archive_sha256" to result.archiveSha256, "format" to result.format,
                    "image_count" to result.images.size, "swift_count" to result.swiftSymbolCount,
                    "cpp_count" to result.cppSymbolCount, "c_count" to result.cSymbolCount,
                    "import_count" to result.importedSymbolCount, "signed_count" to result.signedImageCount,
                    "created_at" to now,
                ),
            ).single()
            val id = row.long("id")
            execute("DELETE FROM pulse_artifact_images WHERE analysis_id = :analysis_id", mapOf("analysis_id" to id))
            result.images.forEach { image -> insertImage(id, image, now) }
            id
        }
        log.info("iOS artifact analysed: version=$versionId sha256=${result.archiveSha256} images=${result.images.size}")
        return result.toReport(reportId, versionId, fileName, bytes.size.toLong(), now)
    }

    suspend fun get(versionId: Long): PulseArtifactReport? {
        val summary = db.fetchAll(
            "SELECT * FROM pulse_artifact_analyses WHERE version_id = :version_id",
            mapOf("version_id" to versionId),
        ).singleOrNull() ?: return null
        val images = db.fetchAll(
            "SELECT * FROM pulse_artifact_images WHERE analysis_id = :analysis_id ORDER BY relative_path, architecture",
            mapOf("analysis_id" to summary.long("id")),
        ).map { row ->
            IosImageAnalysis(
                relativePath = row.string("relative_path"), architecture = row.string("architecture"),
                imageUuid = row.stringOrNull("image_uuid"), textSha256 = row.string("text_sha256"),
                textSize = row.long("text_size"), hasSwiftMetadata = row.boolean("has_swift_metadata"),
                swiftSymbolCount = row.int("swift_symbol_count"), cppSymbolCount = row.int("cpp_symbol_count"),
                cSymbolCount = row.int("c_symbol_count"), importedSymbolCount = row.int("imported_symbol_count"),
                symbolsTruncated = row.boolean("symbols_truncated"),
                swiftSymbols = decodeList(row.string("swift_symbols")), cppSymbols = decodeList(row.string("cpp_symbols")),
                cSymbols = decodeList(row.string("c_symbols")), importedSymbols = decodeList(row.string("imported_symbols")),
                linkedLibraries = decodeList(row.string("linked_libraries")),
                signatureStatus = row.string("signature_status"),
                signingCertificates = json.decodeFromString(row.string("signing_certificates")),
            )
        }
        return PulseArtifactReport(
            id = summary.long("id"), versionId = versionId, fileName = summary.string("file_name"),
            fileSize = summary.long("file_size"), archiveSha256 = summary.string("archive_sha256"),
            format = summary.string("artifact_format"), imageCount = summary.int("image_count"),
            swiftSymbolCount = summary.int("swift_symbol_count"), cppSymbolCount = summary.int("cpp_symbol_count"),
            cSymbolCount = summary.int("c_symbol_count"), importedSymbolCount = summary.int("imported_symbol_count"),
            signedImageCount = summary.int("signed_image_count"), createdAt = summary.long("created_at"), images = images,
        )
    }

    private suspend fun DbContext.insertImage(analysisId: Long, image: IosImageAnalysis, now: Long) {
        execute(
            """
            INSERT INTO pulse_artifact_images (
                analysis_id, relative_path, architecture, image_uuid, text_sha256, text_size,
                has_swift_metadata, swift_symbol_count, cpp_symbol_count, c_symbol_count,
                imported_symbol_count, symbols_truncated, swift_symbols, cpp_symbols, c_symbols,
                imported_symbols, linked_libraries, signature_status, signing_certificates, created_at
            ) VALUES (
                :analysis_id, :path, :architecture, :uuid, :text_sha256, :text_size,
                :has_swift, :swift_count, :cpp_count, :c_count, :import_count, :truncated,
                :swift_symbols, :cpp_symbols, :c_symbols, :imported_symbols, :libraries,
                :signature_status, :certificates, :created_at
            )
            """.trimIndent(),
            mapOf(
                "analysis_id" to analysisId, "path" to image.relativePath, "architecture" to image.architecture,
                "uuid" to image.imageUuid, "text_sha256" to image.textSha256, "text_size" to image.textSize,
                "has_swift" to image.hasSwiftMetadata, "swift_count" to image.swiftSymbolCount,
                "cpp_count" to image.cppSymbolCount, "c_count" to image.cSymbolCount,
                "import_count" to image.importedSymbolCount, "truncated" to image.symbolsTruncated,
                "swift_symbols" to json.encodeToString(image.swiftSymbols),
                "cpp_symbols" to json.encodeToString(image.cppSymbols), "c_symbols" to json.encodeToString(image.cSymbols),
                "imported_symbols" to json.encodeToString(image.importedSymbols),
                "libraries" to json.encodeToString(image.linkedLibraries),
                "signature_status" to image.signatureStatus,
                "certificates" to json.encodeToString(image.signingCertificates), "created_at" to now,
            ),
        )
    }

    private fun decodeList(value: String): List<String> = json.decodeFromString(value)

    private fun IosArtifactAnalysis.toReport(id: Long, versionId: Long, name: String, size: Long, now: Long) =
        PulseArtifactReport(
            id, versionId, name, size, archiveSha256, format, images.size,
            swiftSymbolCount, cppSymbolCount, cSymbolCount, importedSymbolCount, signedImageCount, now, images,
        )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
