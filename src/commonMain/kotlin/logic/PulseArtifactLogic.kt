package logic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.PulsePlatform
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
    /** Runtime evidence is restricted to this managed app and build number. */
    val runtimeBuildNumber: Long? = null,
    val runtimeEvidenceCount: Int = 0,
    val runtimeEvidenceTruncated: Boolean = false,
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
            ?: pulseNotFound(PulseErrorCodes.VERSION_NOT_FOUND)
        val app = PulseAppTable.get(version.appId)
            ?: pulseNotFound(PulseErrorCodes.APP_NOT_FOUND)
        if (app.platform != PulsePlatform.IOS) pulseInvalid(PulseErrorCodes.ARTIFACT_PLATFORM_NOT_IOS)

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
            execute("DELETE FROM pulse_artifact_debug_symbols WHERE analysis_id = :analysis_id", mapOf("analysis_id" to id))
            execute("DELETE FROM pulse_artifact_images WHERE analysis_id = :analysis_id", mapOf("analysis_id" to id))
            result.images.forEach { image -> insertImage(id, image, now) }
            insertDebugSymbols(id, result.debugSymbols)
            id
        }
        log.info("iOS artifact analysed: version=$versionId sha256=${result.archiveSha256} images=${result.images.size}")
        return get(versionId) ?: result.toReport(reportId, versionId, fileName, bytes.size.toLong(), now)
    }

    suspend fun get(versionId: Long): PulseArtifactReport? {
        val summary = db.fetchAll(
            """
            SELECT a.*, v.build_number, p.app_key
            FROM pulse_artifact_analyses a
            JOIN pulse_app_versions v ON v.id = a.version_id
            JOIN pulse_apps p ON p.id = v.app_id
            WHERE a.version_id = :version_id
            """.trimIndent(),
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
                debugSymbolCount = row.int("debug_symbol_count"),
                debugSymbolsTruncated = row.boolean("debug_symbols_truncated"),
                debugSymbolSample = decodeList(row.string("debug_symbol_sample")),
            )
        }
        val evidence = loadRuntimeEvidence(
            analysisId = summary.long("id"),
            appKey = summary.string("app_key"),
            buildNumber = summary.long("build_number"),
            images = images,
        )
        val linkedImages = images.map { image ->
            val uuid = image.imageUuid?.normalizedUuid()
            image.copy(runtimeEvidence = if (uuid == null) emptyList() else evidence.byUuid[uuid].orEmpty())
        }
        return PulseArtifactReport(
            id = summary.long("id"), versionId = versionId, fileName = summary.string("file_name"),
            fileSize = summary.long("file_size"), archiveSha256 = summary.string("archive_sha256"),
            format = summary.string("artifact_format"), imageCount = summary.int("image_count"),
            swiftSymbolCount = summary.int("swift_symbol_count"), cppSymbolCount = summary.int("cpp_symbol_count"),
            cSymbolCount = summary.int("c_symbol_count"), importedSymbolCount = summary.int("imported_symbol_count"),
            signedImageCount = summary.int("signed_image_count"), createdAt = summary.long("created_at"),
            images = linkedImages, runtimeBuildNumber = summary.long("build_number"),
            runtimeEvidenceCount = linkedImages.sumOf { it.runtimeEvidence.size },
            runtimeEvidenceTruncated = evidence.truncated,
        )
    }

    private suspend fun DbContext.insertImage(analysisId: Long, image: IosImageAnalysis, now: Long) {
        execute(
            """
            INSERT INTO pulse_artifact_images (
                analysis_id, relative_path, architecture, image_uuid, text_sha256, text_size,
                has_swift_metadata, swift_symbol_count, cpp_symbol_count, c_symbol_count,
                imported_symbol_count, symbols_truncated, swift_symbols, cpp_symbols, c_symbols,
                imported_symbols, linked_libraries, signature_status, signing_certificates,
                debug_symbol_count, debug_symbols_truncated, debug_symbol_sample, created_at
            ) VALUES (
                :analysis_id, :path, :architecture, :uuid, :text_sha256, :text_size,
                :has_swift, :swift_count, :cpp_count, :c_count, :import_count, :truncated,
                :swift_symbols, :cpp_symbols, :c_symbols, :imported_symbols, :libraries,
                :signature_status, :certificates, :debug_symbol_count, :debug_symbols_truncated,
                :debug_symbol_sample, :created_at
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
                "certificates" to json.encodeToString(image.signingCertificates),
                "debug_symbol_count" to image.debugSymbolCount,
                "debug_symbols_truncated" to image.debugSymbolsTruncated,
                "debug_symbol_sample" to json.encodeToString(image.debugSymbolSample),
                "created_at" to now,
            ),
        )
    }

    private suspend fun DbContext.insertDebugSymbols(analysisId: Long, symbols: List<ArtifactDebugSymbol>) {
        symbols.chunked(250).forEach { chunk ->
            val params = HashMap<String, Any?>(chunk.size * 6)
            val values = chunk.mapIndexed { index, symbol ->
                params["analysis_$index"] = analysisId
                params["uuid_$index"] = symbol.imageUuid.normalizedUuid()
                params["architecture_$index"] = symbol.architecture
                params["address_$index"] = symbol.address
                params["name_$index"] = symbol.name.take(2_048)
                params["language_$index"] = symbol.language
                "(:analysis_$index, :uuid_$index, :architecture_$index, :address_$index, :name_$index, :language_$index)"
            }.joinToString(",")
            execute(
                """
                INSERT INTO pulse_artifact_debug_symbols
                    (analysis_id, image_uuid, architecture, address, symbol_name, language)
                VALUES $values
                """.trimIndent(),
                params,
            )
        }
    }

    private data class RuntimeEvidenceResult(
        val byUuid: Map<String, List<ArtifactRuntimeEvidence>> = emptyMap(),
        val truncated: Boolean = false,
    )

    private data class RawRuntimeEvidence(
        val category: String, val name: String, val uuid: String, val moduleName: String?,
        val callerSymbol: String?, val callsiteOffset: Long?, val stackFingerprint: String?,
        val callPath: String?, val detail: String?, val occurrences: Long, val devices: Long,
        val firstSeenAt: Long, val lastSeenAt: Long,
    )

    /** Join runtime facts to shipped code only through an exact, normalized LC_UUID match. */
    private suspend fun loadRuntimeEvidence(
        analysisId: Long,
        appKey: String,
        buildNumber: Long,
        images: List<IosImageAnalysis>,
    ): RuntimeEvidenceResult {
        val uuids = images.mapNotNull { it.imageUuid?.normalizedUuid() }.distinct()
        if (uuids.isEmpty()) return RuntimeEvidenceResult()
        val params = hashMapOf<String, Any?>("app_key" to appKey, "build_number" to buildNumber)
        val uuidSql = uuids.mapIndexed { index, uuid ->
            params["uuid_$index"] = uuid
            ":uuid_$index"
        }.joinToString(",")
        val rows = db.fetchAll(
            """
            WITH filtered AS (
                SELECT *, lower(replace(source_image_uuid, '-', '')) AS normalized_uuid,
                       CASE WHEN LEFT(LTRIM(attributes), 1) = '{' THEN attributes::jsonb ELSE '{}'::jsonb END AS attrs
                FROM pulse_events
                WHERE kind = 'Runtime' AND project_id = :app_key AND build_number = :build_number
                  AND source_image_uuid IS NOT NULL
                  AND lower(replace(source_image_uuid, '-', '')) IN ($uuidSql)
            ), evidence AS (
                SELECT 'module' AS category, name, normalized_uuid,
                       NULLIF(source_module, '') AS module_name, NULL::text AS caller_symbol,
                       NULL::text AS callsite_offset, NULL::text AS stack_fingerprint,
                       NULL::text AS call_path,
                       COALESCE(NULLIF(attrs->>'relative_path', ''), NULLIF(source_module, '')) AS detail,
                       COUNT(*)::text AS occurrences, COUNT(DISTINCT device_id)::text AS devices,
                       MIN(ts)::text AS first_seen_at, MAX(ts)::text AS last_seen_at
                FROM filtered WHERE name = 'module_artifact'
                GROUP BY name, normalized_uuid, source_module, attrs->>'relative_path'

                UNION ALL
                SELECT 'method', name, normalized_uuid, NULLIF(source_module, ''), NULL::text,
                       NULL::text, NULLIF(attrs->>'methods_digest', ''), NULL::text,
                       CONCAT_WS(' ', NULLIF(attrs->>'class_name', ''),
                           'count=' || COALESCE(NULLIF(attrs->>'method_count', ''), '0'),
                           NULLIF(attrs->>'instance_methods', ''), NULLIF(attrs->>'class_methods', '')),
                       COUNT(*)::text, COUNT(DISTINCT device_id)::text, MIN(ts)::text, MAX(ts)::text
                FROM filtered WHERE name = 'objc_class_inventory'
                GROUP BY name, normalized_uuid, source_module, attrs->>'methods_digest', attrs->>'class_name',
                         attrs->>'method_count', attrs->>'instance_methods', attrs->>'class_methods'

                UNION ALL
                SELECT CASE WHEN name = 'network_request' THEN 'network'
                            WHEN name LIKE '%permission_request' THEN 'permission' ELSE 'api' END,
                       name, normalized_uuid, NULLIF(source_module, ''),
                       NULLIF(attrs->>'caller_symbol', ''), NULLIF(attrs->>'callsite_offset', ''),
                       NULLIF(attrs->>'stack_fingerprint', ''), NULLIF(attrs->>'call_path', ''),
                       CASE WHEN name = 'network_request' THEN NULLIF(attrs->>'detail', '')
                            ELSE CONCAT_WS('.', NULLIF(attrs->>'api_class', ''), NULLIF(attrs->>'api_selector', '')) END,
                       SUM(CASE WHEN attrs->>'call_count' ~ '^[0-9]+$'
                                THEN (attrs->>'call_count')::bigint ELSE 1 END)::text,
                       COUNT(DISTINCT device_id)::text, MIN(ts)::text, MAX(ts)::text
                FROM filtered
                WHERE name = 'network_request' OR NULLIF(attrs->>'api_class', '') IS NOT NULL
                   OR name LIKE '%permission_request'
                GROUP BY name, normalized_uuid, source_module, attrs->>'caller_symbol',
                         attrs->>'callsite_offset', attrs->>'stack_fingerprint', attrs->>'call_path',
                         attrs->>'detail', attrs->>'api_class', attrs->>'api_selector'
            )
            SELECT *, COUNT(*) OVER()::text AS total_count
            FROM evidence
            ORDER BY normalized_uuid, occurrences DESC, last_seen_at DESC
            LIMIT $MAX_RUNTIME_EVIDENCE_GROUPS
            """.trimIndent(),
            params,
        )
        val imageByName = images.associateBy { it.relativePath.substringAfterLast('/') }
        if (rows.isEmpty()) return RuntimeEvidenceResult()
        val raw = rows.map { row ->
            RawRuntimeEvidence(
                category = row.string("category"), name = row.string("name"),
                uuid = row.string("normalized_uuid"), moduleName = row.stringOrNull("module_name"),
                callerSymbol = row.stringOrNull("caller_symbol"),
                callsiteOffset = row.stringOrNull("callsite_offset")?.toLongOrNull(),
                stackFingerprint = row.stringOrNull("stack_fingerprint"), callPath = row.stringOrNull("call_path"),
                detail = row.stringOrNull("detail"), occurrences = row.string("occurrences").toLongOrNull() ?: 0,
                devices = row.string("devices").toLongOrNull() ?: 0,
                firstSeenAt = row.string("first_seen_at").toLongOrNull() ?: 0,
                lastSeenAt = row.string("last_seen_at").toLongOrNull() ?: 0,
            )
        }
        val imageUuidsByName = imageByName.mapValues { entry -> entry.value.imageUuid?.normalizedUuid() }
        val targets = buildSet {
            raw.forEach { item ->
                item.callsiteOffset?.let { add(SymbolLookup(item.uuid, it)) }
                item.callPath?.let { addAll(ArtifactSymbolResolver.targets(it, imageUuidsByName)) }
            }
        }
        val resolved = loadResolvedSymbols(analysisId, targets)
        val linked = raw.map { item ->
            item.uuid to ArtifactRuntimeEvidence(
                category = item.category, name = item.name, moduleName = item.moduleName,
                callerSymbol = item.callerSymbol, callsiteOffset = item.callsiteOffset,
                resolvedCallerSymbol = item.callsiteOffset?.let { resolved[SymbolLookup(item.uuid, it)] },
                stackFingerprint = item.stackFingerprint, callPath = item.callPath,
                resolvedCallPath = item.callPath?.let { path ->
                    ArtifactSymbolResolver.resolvePath(path, imageUuidsByName) { uuid, offset ->
                        resolved[SymbolLookup(uuid, offset)]
                    }
                },
                detail = item.detail, occurrences = item.occurrences, devices = item.devices,
                firstSeenAt = item.firstSeenAt, lastSeenAt = item.lastSeenAt,
            )
        }.groupBy({ it.first }, { it.second })
        val total = rows.first().string("total_count").toIntOrNull() ?: rows.size
        return RuntimeEvidenceResult(linked, total > rows.size)
    }

    private suspend fun loadResolvedSymbols(
        analysisId: Long,
        targets: Set<SymbolLookup>,
    ): Map<SymbolLookup, String> {
        if (targets.isEmpty()) return emptyMap()
        val params = hashMapOf<String, Any?>("analysis_id" to analysisId)
        val values = targets.mapIndexed { index, target ->
            params["target_uuid_$index"] = target.uuid
            params["target_address_$index"] = target.offset
            "(CAST(:target_uuid_$index AS text), CAST(:target_address_$index AS bigint))"
        }.joinToString(",")
        return db.fetchAll(
            """
            WITH targets(image_uuid, target_address) AS (VALUES $values)
            SELECT targets.image_uuid, targets.target_address, symbol.address, symbol.symbol_name
            FROM targets
            LEFT JOIN LATERAL (
                SELECT address, symbol_name
                FROM pulse_artifact_debug_symbols
                WHERE analysis_id = :analysis_id AND image_uuid = targets.image_uuid
                  AND address <= targets.target_address
                ORDER BY address DESC
                LIMIT 1
            ) symbol ON TRUE
            """.trimIndent(),
            params,
        ).mapNotNull { row ->
            val symbolAddress = row.longOrNull("address") ?: return@mapNotNull null
            val targetAddress = row.long("target_address")
            val name = row.stringOrNull("symbol_name") ?: return@mapNotNull null
            val delta = targetAddress - symbolAddress
            SymbolLookup(row.string("image_uuid"), targetAddress) to
                if (delta == 0L) name else "$name+0x${delta.toString(16)}"
        }.toMap()
    }

    private fun String.normalizedUuid(): String = replace("-", "").lowercase()

    private fun decodeList(value: String): List<String> = json.decodeFromString(value)

    private fun IosArtifactAnalysis.toReport(id: Long, versionId: Long, name: String, size: Long, now: Long) =
        PulseArtifactReport(
            id, versionId, name, size, archiveSha256, format, images.size,
            swiftSymbolCount, cppSymbolCount, cSymbolCount, importedSymbolCount, signedImageCount, now, images,
        )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val MAX_RUNTIME_EVIDENCE_GROUPS = 1_000
    }
}

internal data class ArtifactStoredDebugSymbol(val address: Long, val name: String)
internal data class SymbolLookup(val uuid: String, val offset: Long)

internal object ArtifactSymbolResolver {
    private val callPathFrame = Regex("""([^!>]+)!([^+]+)\+0x([0-9a-fA-F]+)""")

    fun resolve(symbols: List<ArtifactStoredDebugSymbol>?, offset: Long): String? {
        if (symbols.isNullOrEmpty()) return null
        var low = 0
        var high = symbols.lastIndex
        var candidate = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (symbols[mid].address <= offset) { candidate = mid; low = mid + 1 } else high = mid - 1
        }
        if (candidate < 0) return null
        val symbol = symbols[candidate]
        val delta = offset - symbol.address
        return if (delta == 0L) symbol.name else "${symbol.name}+0x${delta.toString(16)}"
    }

    fun resolvePath(
        path: String,
        imageUuidsByName: Map<String, String?>,
        symbols: Map<String, List<ArtifactStoredDebugSymbol>>,
    ): String = resolvePath(path, imageUuidsByName) { uuid, offset -> resolve(symbols[uuid], offset) }

    fun targets(path: String, imageUuidsByName: Map<String, String?>): Set<SymbolLookup> =
        callPathFrame.findAll(path).mapNotNull { match ->
            val imageName = match.groupValues[1].trim()
            val offset = match.groupValues[3].toLongOrNull(16) ?: return@mapNotNull null
            val uuid = imageUuidsByName[imageName] ?: return@mapNotNull null
            SymbolLookup(uuid, offset)
        }.toSet()

    fun resolvePath(
        path: String,
        imageUuidsByName: Map<String, String?>,
        resolve: (String, Long) -> String?,
    ): String = callPathFrame.replace(path) { match ->
        val rawImageName = match.groupValues[1]
        val imageName = rawImageName.trim()
        val leadingWhitespace = rawImageName.takeWhile { it.isWhitespace() }
        val offset = match.groupValues[3].toLongOrNull(16) ?: return@replace match.value
        val uuid = imageUuidsByName[imageName] ?: return@replace match.value
        val resolved = resolve(uuid, offset) ?: return@replace match.value
        "$leadingWhitespace$imageName!$resolved"
    }
}
