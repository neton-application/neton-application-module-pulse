@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package logic

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import neton.core.http.BadRequestException
import neton.security.digest.Sha256
import platform.posix.EINTR
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.X_OK
import platform.posix.access
import platform.posix.close
import platform.posix.closedir
import platform.posix.errno
import platform.posix.getpid
import platform.posix.lstat
import platform.posix.mkdir
import platform.posix.open
import platform.posix.opendir
import platform.posix.read
import platform.posix.readdir
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.system
import platform.posix.unlink
import platform.posix.write

@Serializable
data class SigningCertificateEvidence(
    val sha256: String,
    val commonName: String? = null,
    val organization: String? = null,
    val organizationalUnit: String? = null,
)

@Serializable
data class IosImageAnalysis(
    val relativePath: String,
    val architecture: String,
    val imageUuid: String? = null,
    val textSha256: String,
    val textSize: Long,
    val hasSwiftMetadata: Boolean,
    val swiftSymbolCount: Int,
    val cppSymbolCount: Int,
    val cSymbolCount: Int,
    val importedSymbolCount: Int,
    val symbolsTruncated: Boolean,
    val swiftSymbols: List<String>,
    val cppSymbols: List<String>,
    val cSymbols: List<String>,
    val importedSymbols: List<String>,
    val linkedLibraries: List<String>,
    val signatureStatus: String,
    val signingCertificates: List<SigningCertificateEvidence>,
    /** Functions recovered from the dSYM whose LC_UUID and architecture match this image. */
    val debugSymbolCount: Int = 0,
    val debugSymbolsTruncated: Boolean = false,
    val debugSymbolSample: List<String> = emptyList(),
    /** Runtime facts joined by the server; never persisted as part of static analysis. */
    val runtimeEvidence: List<ArtifactRuntimeEvidence> = emptyList(),
)

@Serializable
data class ArtifactRuntimeEvidence(
    val category: String,
    val name: String,
    val moduleName: String? = null,
    val callerSymbol: String? = null,
    val callsiteOffset: Long? = null,
    val resolvedCallerSymbol: String? = null,
    val stackFingerprint: String? = null,
    val callPath: String? = null,
    val resolvedCallPath: String? = null,
    val detail: String? = null,
    val occurrences: Long = 0,
    val devices: Long = 0,
    val firstSeenAt: Long = 0,
    val lastSeenAt: Long = 0,
)

data class ArtifactDebugSymbol(
    val imageUuid: String,
    val architecture: String,
    val address: Long,
    val name: String,
    val language: String,
)

@Serializable
data class IosArtifactAnalysis(
    val format: String,
    val archiveSha256: String,
    val images: List<IosImageAnalysis>,
    @Transient val debugSymbols: List<ArtifactDebugSymbol> = emptyList(),
) {
    val swiftSymbolCount: Int get() = images.sumOf { it.swiftSymbolCount }
    val cppSymbolCount: Int get() = images.sumOf { it.cppSymbolCount }
    val cSymbolCount: Int get() = images.sumOf { it.cSymbolCount }
    val importedSymbolCount: Int get() = images.sumOf { it.importedSymbolCount }
    val signedImageCount: Int get() = images.count { it.signatureStatus == "signed" }
}

/**
 * Analyses the binaries in a final IPA or a zipped XCArchive without executing any code from it.
 * Mach-O parsing is implemented here rather than delegated to otool/nm/codesign so the same
 * server binary behaves on Linux and macOS. `unzip` is used only as a container decoder.
 */
object IosArtifactAnalyzer {

    internal data class DebugAssociation(
        val images: List<IosImageAnalysis>,
        val symbols: List<ArtifactDebugSymbol>,
    )

    fun analyze(fileName: String, archive: ByteArray): IosArtifactAnalysis {
        if (archive.isEmpty()) throw BadRequestException("制品文件为空")
        if (archive.size > MAX_ARCHIVE_BYTES) throw BadRequestException("制品超过 512 MiB 上限")
        val lower = fileName.lowercase()
        if (!lower.endsWith(".ipa") && !lower.endsWith(".zip")) {
            throw BadRequestException("仅支持 .ipa 或压缩后的 .xcarchive.zip")
        }
        if (access("/usr/bin/unzip", X_OK) != 0) throw BadRequestException("分析服务器未安装 unzip")
        validateZipStructure(archive)

        val root = tempDirectory()
        try {
            val zip = "$root/artifact.zip"
            writeFile(zip, archive)
            if (system("/usr/bin/unzip -qq $zip -d $root/extracted") != 0) {
                throw BadRequestException("无法解压制品，请确认文件是有效 IPA/XCArchive ZIP")
            }
            val files = regularFiles("$root/extracted")
            val releaseImages = ArrayList<IosImageAnalysis>()
            val debugImages = ArrayList<MachOAnalyzer.AnalyzedImage>()
            var scannedBytes = 0L
            for (path in files) {
                val magic = readPrefix(path, 4) ?: continue
                if (!MachOAnalyzer.hasMachOMagic(magic)) continue
                val bytes = readFile(path, MAX_IMAGE_BYTES) ?: continue
                scannedBytes += bytes.size
                if (scannedBytes > MAX_EXPANDED_BYTES) throw BadRequestException("解压后制品超过 2 GiB 安全上限")
                val relative = path.removePrefix("$root/extracted/")
                val parsed = MachOAnalyzer.analyzeDetailed(relative, bytes)
                if (isDsymImage(relative)) debugImages += parsed else releaseImages += parsed.map { it.image }
                if (releaseImages.size + debugImages.size > MAX_IMAGES) {
                    throw BadRequestException("Mach-O 镜像数量超过安全上限")
                }
            }
            if (releaseImages.isEmpty()) throw BadRequestException("制品内没有找到发布用 Mach-O 镜像")
            val associated = associateDebugSymbols(releaseImages, debugImages)
            val images = associated.images
            val allDebugSymbols = associated.symbols
            val format = if (files.any { ".xcarchive/" in it }) "xcarchive" else "ipa"
            return IosArtifactAnalysis(format, Sha256.hex(archive), images, allDebugSymbols)
        } finally {
            removeTree(root)
        }
    }

    internal fun associateDebugSymbols(
        releaseImages: List<IosImageAnalysis>,
        debugImages: List<MachOAnalyzer.AnalyzedImage>,
    ): DebugAssociation {
        val debugByIdentity = debugImages
            .mapNotNull { debug ->
                debug.image.imageUuid?.let { imageIdentity(it, debug.image.architecture) to debug }
            }
            .toMap()
            val allDebugSymbols = ArrayList<ArtifactDebugSymbol>()
            var remaining = MAX_DEBUG_SYMBOLS_PER_ARTIFACT
            val images = releaseImages.map { image ->
                val debug = image.imageUuid?.let { debugByIdentity[imageIdentity(it, image.architecture)] }
                if (debug == null) image else {
                    val accepted = debug.definedSymbols.take(remaining)
                    remaining -= accepted.size
                    val uuid = image.imageUuid!!
                    allDebugSymbols += accepted.map { symbol ->
                        ArtifactDebugSymbol(uuid, image.architecture, symbol.address, symbol.name, symbol.language)
                    }
                    image.copy(
                        debugSymbolCount = debug.definedSymbolCount,
                        debugSymbolsTruncated = debug.definedSymbolsTruncated || accepted.size < debug.definedSymbols.size,
                        debugSymbolSample = debug.definedSymbols.take(MAX_DEBUG_SYMBOL_SAMPLE).map { it.name },
                    )
                }
            }
        return DebugAssociation(images, allDebugSymbols)
    }

    /** Validate the central directory before invoking unzip, including symlink-pivot attacks. */
    internal fun validateZipStructure(zip: ByteArray) {
        val searchStart = (zip.size - 22).coerceAtLeast(0)
        val searchEnd = (zip.size - 65_557).coerceAtLeast(0)
        var eocd = -1
        for (p in searchStart downTo searchEnd) {
            if (p + 4 <= zip.size && u32le(zip, p) == ZIP_EOCD) { eocd = p; break }
        }
        if (eocd < 0 || eocd + 22 > zip.size) throw BadRequestException("不是有效的 ZIP 制品")
        val disk = u16le(zip, eocd + 4)
        val centralDisk = u16le(zip, eocd + 6)
        val entries = u16le(zip, eocd + 10)
        val centralSize = u32le(zip, eocd + 12).toLong()
        val centralOffset = u32le(zip, eocd + 16).toLong()
        val commentLength = u16le(zip, eocd + 20)
        if (disk != 0 || centralDisk != 0) throw BadRequestException("不支持分卷 ZIP")
        if (entries !in 1..MAX_ZIP_ENTRIES || centralOffset + centralSize != eocd.toLong() ||
            eocd.toLong() + 22 + commentLength != zip.size.toLong()) {
            throw BadRequestException("ZIP 中央目录异常")
        }
        val seen = HashSet<String>(entries)
        var expanded = 0L
        var p = centralOffset.toInt()
        repeat(entries) {
            if (p + 46 > zip.size || u32le(zip, p) != ZIP_CENTRAL_FILE) throw BadRequestException("ZIP 条目损坏")
            val madeBy = u16le(zip, p + 4)
            val flags = u16le(zip, p + 8)
            val compressed = u32le(zip, p + 20)
            val uncompressed = u32le(zip, p + 24)
            val nameLength = u16le(zip, p + 28)
            val extraLength = u16le(zip, p + 30)
            val commentLength = u16le(zip, p + 32)
            val external = u32le(zip, p + 38)
            val end = p.toLong() + 46 + nameLength + extraLength + commentLength
            if (end > zip.size || compressed == UInt.MAX_VALUE || uncompressed == UInt.MAX_VALUE) {
                throw BadRequestException("不支持 ZIP64 或损坏的 ZIP 条目")
            }
            if (flags and 0x1 != 0) throw BadRequestException("不支持加密 ZIP")
            val name = zip.copyOfRange(p + 46, p + 46 + nameLength).decodeToString().replace('\\', '/')
            val segments = name.split('/')
            if (name.isBlank() || name.startsWith('/') || segments.any { it == ".." } || '\u0000' in name || !seen.add(name)) {
                throw BadRequestException("ZIP 包含不安全或重复路径")
            }
            // Unix file type lives in the high 16 bits of external attributes. Unzip can follow a
            // symlink created by an earlier entry, so it has to be rejected before extraction.
            if (madeBy ushr 8 == 3) {
                val fileType = (external.toInt() ushr 16) and 0xf000
                if (fileType == 0xa000) throw BadRequestException("ZIP 不允许符号链接")
            }
            expanded += uncompressed.toLong()
            if (expanded > MAX_EXPANDED_BYTES) throw BadRequestException("解压后制品超过 2 GiB 安全上限")
            p = end.toInt()
        }
        if (p.toLong() != centralOffset + centralSize) throw BadRequestException("ZIP 中央目录长度不一致")
    }

    private fun tempDirectory(): String {
        repeat(100) { attempt ->
            val path = "/tmp/pulse-artifact-${getpid()}-${kotlin.time.Clock.System.now().toEpochMilliseconds()}-$attempt"
            if (mkdir(path, 0x1c0u) == 0) return path // 0700
        }
        throw BadRequestException("无法创建制品分析临时目录")
    }

    private fun writeFile(path: String, bytes: ByteArray) {
        val fd = open(path, O_CREAT or O_TRUNC or O_WRONLY, 0x180) // 0600
        if (fd < 0) throw BadRequestException("无法保存待分析制品")
        try {
            var offset = 0
            while (offset < bytes.size) {
                val n = bytes.usePinned { write(fd, it.addressOf(offset), (bytes.size - offset).convert()) }
                if (n < 0 && errno == EINTR) continue
                if (n <= 0) throw BadRequestException("制品写入不完整")
                offset += n.toInt()
            }
        } finally {
            close(fd)
        }
    }

    private fun readFile(path: String, maxBytes: Int): ByteArray? {
        val size = memScoped {
            val st = alloc<stat>()
            if (lstat(path, st.ptr) != 0) return null
            st.st_size
        }
        if (size < 0L) return null
        if (size > maxBytes) throw BadRequestException("单个 Mach-O 镜像超过 512 MiB 上限")
        val fd = open(path, O_RDONLY)
        if (fd < 0) return null
        try {
            val result = ByteArray(size.toInt())
            var offset = 0
            while (offset < result.size) {
                val n = result.usePinned { read(fd, it.addressOf(offset), (result.size - offset).convert()) }
                if (n < 0 && errno == EINTR) continue
                if (n < 0) return null
                if (n == 0L) return null
                offset += n.toInt()
            }
            return result
        } finally {
            close(fd)
        }
    }

    private fun readPrefix(path: String, size: Int): ByteArray? {
        val fd = open(path, O_RDONLY)
        if (fd < 0) return null
        return try {
            val result = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val n = result.usePinned { read(fd, it.addressOf(offset), (size - offset).convert()) }
                if (n < 0 && errno == EINTR) continue
                if (n <= 0) return null
                offset += n.toInt()
            }
            result
        } finally {
            close(fd)
        }
    }

    private fun regularFiles(root: String): List<String> {
        val out = ArrayList<String>()
        fun walk(path: String) {
            val dir = opendir(path) ?: return
            try {
                while (true) {
                    val entry = readdir(dir) ?: break
                    val name = entry.pointed.d_name.toKString()
                    if (name == "." || name == "..") continue
                    val child = "$path/$name"
                    memScoped {
                        val st = alloc<stat>()
                        if (lstat(child, st.ptr) != 0) return@memScoped
                        when (st.st_mode.toInt() and S_IFMT) {
                            S_IFDIR -> walk(child)
                            S_IFREG -> out += child
                        }
                    }
                }
            } finally {
                closedir(dir)
            }
        }
        walk(root)
        return out
    }

    private fun removeTree(path: String) {
        val dir = opendir(path)
        if (dir == null) { unlink(path); return }
        try {
            while (true) {
                val entry = readdir(dir) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                removeTree("$path/$name")
            }
        } finally {
            closedir(dir)
        }
        rmdir(path)
    }

    private const val MAX_ARCHIVE_BYTES = 512 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 512 * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 100_000
    private const val MAX_IMAGES = 1_024
    private const val MAX_DEBUG_SYMBOLS_PER_ARTIFACT = 250_000
    private const val MAX_DEBUG_SYMBOL_SAMPLE = 100
    private const val ZIP_EOCD = 0x06054b50U
    private const val ZIP_CENTRAL_FILE = 0x02014b50U

    private fun isDsymImage(path: String): Boolean =
        ".dSYM/Contents/Resources/DWARF/" in path

    private fun imageIdentity(uuid: String, architecture: String): String =
        uuid.replace("-", "").lowercase() + ":" + architecture
}

internal object MachOAnalyzer {
    data class DefinedSymbol(val address: Long, val name: String, val language: String)
    data class AnalyzedImage(
        val image: IosImageAnalysis,
        val definedSymbols: List<DefinedSymbol>,
        val definedSymbolCount: Int,
        val definedSymbolsTruncated: Boolean,
    )

    fun hasMachOMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val be = u32be(bytes, 0)
        val le = u32le(bytes, 0)
        return be == FAT_MAGIC || be == FAT_MAGIC_64 ||
            le == MH_MAGIC || le == MH_MAGIC_64
    }

    fun analyze(path: String, bytes: ByteArray): List<IosImageAnalysis> {
        return analyzeDetailed(path, bytes).map { it.image }
    }

    fun analyzeDetailed(path: String, bytes: ByteArray): List<AnalyzedImage> {
        if (bytes.size < 4) return emptyList()
        return when (u32be(bytes, 0)) {
            FAT_MAGIC, FAT_MAGIC_64 -> analyzeFat(path, bytes, u32be(bytes, 0) == FAT_MAGIC_64)
            else -> analyzeSlice(path, bytes, 0, bytes.size)?.let(::listOf).orEmpty()
        }
    }

    private fun analyzeFat(path: String, bytes: ByteArray, is64: Boolean): List<AnalyzedImage> {
        if (bytes.size < 8) return emptyList()
        val count = u32be(bytes, 4).toInt()
        if (count !in 1..32) return emptyList()
        val stride = if (is64) 32 else 20
        val out = ArrayList<AnalyzedImage>()
        repeat(count) { i ->
            val p = 8 + i * stride
            if (p + stride > bytes.size) return@repeat
            val offset = if (is64) u64be(bytes, p + 8) else u32be(bytes, p + 8).toULong()
            val size = if (is64) u64be(bytes, p + 16) else u32be(bytes, p + 12).toULong()
            if (offset > Int.MAX_VALUE.toULong() || size > Int.MAX_VALUE.toULong()) return@repeat
            analyzeSlice(path, bytes, offset.toInt(), size.toInt())?.let(out::add)
        }
        return out
    }

    private fun analyzeSlice(path: String, file: ByteArray, base: Int, sliceSize: Int): AnalyzedImage? {
        if (base < 0 || sliceSize < 28 || base.toLong() + sliceSize > file.size) return null
        val magic = u32le(file, base)
        val is64 = when (magic) { MH_MAGIC_64 -> true; MH_MAGIC -> false; else -> return null }
        val headerSize = if (is64) 32 else 28
        val cpu = u32le(file, base + 4).toInt()
        val subtype = u32le(file, base + 8).toInt()
        val ncmds = u32le(file, base + 16).toInt()
        val sizeofcmds = u32le(file, base + 20).toInt()
        if (ncmds !in 0..MAX_LOAD_COMMANDS || sizeofcmds < 0 || headerSize.toLong() + sizeofcmds > sliceSize) return null

        var uuid: String? = null
        var textOffset = 0
        var textSize = 0
        var symoff = 0
        var nsyms = 0
        var stroff = 0
        var strsize = 0
        var codeSigOffset = 0
        var codeSigSize = 0
        var hasSwiftMetadata = false
        val libraries = LinkedHashSet<String>()

        var command = base + headerSize
        repeat(ncmds) {
            if (command.toLong() + 8 > base.toLong() + sliceSize) return null
            val cmd = u32le(file, command)
            val cmdsize = u32le(file, command + 4).toInt()
            if (cmdsize < 8 || command.toLong() + cmdsize > base.toLong() + sliceSize) return null
            when (cmd) {
                LC_UUID -> if (cmdsize >= 24) uuid = formatUuid(file, command + 8)
                LC_SYMTAB -> if (cmdsize >= 24) {
                    symoff = u32le(file, command + 8).toInt(); nsyms = u32le(file, command + 12).toInt()
                    stroff = u32le(file, command + 16).toInt(); strsize = u32le(file, command + 20).toInt()
                }
                LC_CODE_SIGNATURE -> if (cmdsize >= 16) {
                    codeSigOffset = u32le(file, command + 8).toInt(); codeSigSize = u32le(file, command + 12).toInt()
                }
                LC_SEGMENT_64 -> if (is64 && cmdsize >= 72) {
                    val segment = ascii(file, command + 8, 16)
                    if (segment == "__TEXT") {
                        textOffset = u64le(file, command + 40).coerceAtMost(Int.MAX_VALUE.toULong()).toInt()
                        textSize = u64le(file, command + 48).coerceAtMost(Int.MAX_VALUE.toULong()).toInt()
                    }
                    val sections = u32le(file, command + 64).toInt().coerceAtMost(4_096)
                    for (s in 0 until sections) {
                        val sp = command + 72 + s * 80
                        if (sp + 80 > command + cmdsize) break
                        if (ascii(file, sp, 16).startsWith("__swift5_")) hasSwiftMetadata = true
                    }
                }
                LC_SEGMENT -> if (!is64 && cmdsize >= 56) {
                    val segment = ascii(file, command + 8, 16)
                    if (segment == "__TEXT") {
                        textOffset = u32le(file, command + 32).toInt(); textSize = u32le(file, command + 36).toInt()
                    }
                    val sections = u32le(file, command + 48).toInt().coerceAtMost(4_096)
                    for (s in 0 until sections) {
                        val sp = command + 56 + s * 68
                        if (sp + 68 > command + cmdsize) break
                        if (ascii(file, sp, 16).startsWith("__swift5_")) hasSwiftMetadata = true
                    }
                }
                in DYLIB_COMMANDS -> {
                    if (cmdsize < 12) return null
                    val nameOffset = u32le(file, command + 8).toInt()
                    if (nameOffset in 12 until cmdsize) cString(file, command + nameOffset, command + cmdsize)?.let(libraries::add)
                }
            }
            command += cmdsize
        }

        val symbols = parseSymbols(file, base, sliceSize, is64, symoff, nsyms, stroff, strsize)
        val validTextRange = textOffset >= 0 && textSize >= 0 &&
            textOffset.toLong() + textSize <= sliceSize
        val textStart = base.toLong() + textOffset
        val text = if (validTextRange && textStart + textSize <= file.size) {
            file.copyOfRange(textStart.toInt(), (textStart + textSize).toInt())
        } else ByteArray(0)
        val signature = parseSignature(file, base, sliceSize, codeSigOffset, codeSigSize)
        val image = IosImageAnalysis(
            relativePath = path,
            architecture = architecture(cpu, subtype),
            imageUuid = uuid,
            textSha256 = Sha256.hex(text),
            textSize = text.size.toLong(),
            hasSwiftMetadata = hasSwiftMetadata,
            swiftSymbolCount = symbols.swiftCount,
            cppSymbolCount = symbols.cppCount,
            cSymbolCount = symbols.cCount,
            importedSymbolCount = symbols.importCount,
            symbolsTruncated = symbols.truncated,
            swiftSymbols = symbols.swift,
            cppSymbols = symbols.cpp,
            cSymbols = symbols.c,
            importedSymbols = symbols.imported,
            linkedLibraries = libraries.take(MAX_REPORTED_ITEMS),
            signatureStatus = signature.first,
            signingCertificates = signature.second,
        )
        return AnalyzedImage(image, symbols.defined, symbols.definedCount, symbols.definedTruncated)
    }

    private data class Symbols(
        val swift: List<String>, val cpp: List<String>, val c: List<String>, val imported: List<String>,
        val swiftCount: Int, val cppCount: Int, val cCount: Int, val importCount: Int, val truncated: Boolean,
        val defined: List<DefinedSymbol>, val definedCount: Int, val definedTruncated: Boolean,
    )

    private fun parseSymbols(
        file: ByteArray, base: Int, sliceSize: Int, is64: Boolean,
        symoff: Int, nsyms: Int, stroff: Int, strsize: Int,
    ): Symbols {
        val stride = if (is64) 16 else 12
        if (nsyms !in 1..MAX_SYMBOLS || symoff < 0 || stroff < 0 || strsize < 0 ||
            symoff.toLong() + nsyms.toLong() * stride > sliceSize || stroff.toLong() + strsize > sliceSize
        ) return Symbols(emptyList(), emptyList(), emptyList(), emptyList(), 0, 0, 0, 0, false, emptyList(), 0, false)
        val swift = ArrayList<String>(); val cpp = ArrayList<String>(); val c = ArrayList<String>(); val imports = ArrayList<String>()
        val defined = ArrayList<DefinedSymbol>()
        var swiftCount = 0; var cppCount = 0; var cCount = 0; var importCount = 0
        var definedCount = 0
        repeat(nsyms) { i ->
            val p = base + symoff + i * stride
            val strx = u32le(file, p).toInt()
            if (strx !in 1 until strsize) return@repeat
            val raw = cString(file, base + stroff + strx, base + stroff + strsize)?.take(MAX_SYMBOL_LENGTH) ?: return@repeat
            val type = file[p + 4].toInt() and 0xff
            val external = type and N_EXT != 0
            val undefined = type and N_TYPE == N_UNDF
            if (type and N_STAB != 0) return@repeat
            if (undefined && external) {
                importCount++; if (imports.size < MAX_REPORTED_ITEMS) imports += raw
                return@repeat
            }
            if (undefined) return@repeat
            val normalized = raw.removePrefix("_")
            val language = when {
                normalized.startsWith("\$s") || normalized.startsWith("\$S") || normalized.startsWith("_T") -> {
                    swiftCount++; if (swift.size < MAX_REPORTED_ITEMS) swift += raw; "swift"
                }
                normalized.startsWith("_Z") -> {
                    cppCount++; if (cpp.size < MAX_REPORTED_ITEMS) cpp += raw; "cpp"
                }
                !normalized.startsWith("OBJC_") -> {
                    cCount++; if (c.size < MAX_REPORTED_ITEMS) c += raw; "c"
                }
                else -> "objc"
            }
            val address = if (is64) u64le(file, p + 8).toLong() else u32le(file, p + 8).toLong()
            if (address > 0) {
                definedCount++
                if (defined.size < MAX_DEBUG_SYMBOLS_PER_IMAGE) defined += DefinedSymbol(address, raw, language)
            }
        }
        return Symbols(swift, cpp, c, imports, swiftCount, cppCount, cCount, importCount,
            swiftCount > swift.size || cppCount > cpp.size || cCount > c.size || importCount > imports.size,
            defined.distinctBy { it.address to it.name }.sortedBy { it.address }, definedCount,
            definedCount > defined.size)
    }

    private fun parseSignature(
        file: ByteArray, base: Int, sliceSize: Int, offset: Int, size: Int,
    ): Pair<String, List<SigningCertificateEvidence>> {
        if (offset == 0 && size == 0) return "unsigned" to emptyList()
        if (offset <= 0 || size < 12 || offset.toLong() + size > sliceSize) return "invalid" to emptyList()
        val start = base + offset
        if (u32be(file, start) != CSMAGIC_EMBEDDED_SIGNATURE) return "invalid" to emptyList()
        val length = u32be(file, start + 4).toInt().coerceAtMost(size)
        val count = u32be(file, start + 8).toInt()
        if (count !in 0..1_024 || 12 + count * 8 > length) return "invalid" to emptyList()
        var hasCodeDirectory = false
        var cms: ByteArray? = null
        repeat(count) { i ->
            val entry = start + 12 + i * 8
            val type = u32be(file, entry)
            val blobOffset = u32be(file, entry + 4).toInt()
            if (blobOffset < 0 || blobOffset.toLong() + 8 > length) return@repeat
            val blobStart = start + blobOffset
            val blobLength = u32be(file, blobStart + 4).toInt()
            if (blobLength !in 8..(length - blobOffset)) return@repeat
            if (type == CSSLOT_CODEDIRECTORY || type in CSSLOT_ALTERNATE_CODEDIRECTORIES) hasCodeDirectory = true
            if (type == CSSLOT_SIGNATURESLOT && u32be(file, blobStart) == CSMAGIC_BLOBWRAPPER) {
                cms = file.copyOfRange(blobStart + 8, blobStart + blobLength)
            }
        }
        val certs = cms?.let(DerCertificates::extract).orEmpty()
        return when {
            cms != null -> "signed" to certs
            hasCodeDirectory -> "adhoc" to emptyList()
            else -> "invalid" to emptyList()
        }
    }

    private fun architecture(cpu: Int, subtype: Int): String = when (cpu) {
        CPU_TYPE_ARM64 -> if (subtype and 0xff == 2) "arm64e" else "arm64"
        CPU_TYPE_X86_64 -> "x86_64"
        CPU_TYPE_ARM -> "arm"
        CPU_TYPE_X86 -> "i386"
        else -> "cpu-$cpu-$subtype"
    }

    private const val MH_MAGIC = 0xfeedfaceU
    private const val MH_MAGIC_64 = 0xfeedfacfU
    private const val FAT_MAGIC = 0xcafebabeU
    private const val FAT_MAGIC_64 = 0xcafebabfU
    private const val LC_SEGMENT = 0x1U
    private const val LC_SYMTAB = 0x2U
    private const val LC_SEGMENT_64 = 0x19U
    private const val LC_UUID = 0x1bU
    private const val LC_CODE_SIGNATURE = 0x1dU
    private val DYLIB_COMMANDS = setOf(0xcU, 0x80000018U, 0x8000001fU, 0x20U, 0x80000023U)
    private const val N_EXT = 0x01
    private const val N_TYPE = 0x0e
    private const val N_UNDF = 0x00
    private const val N_STAB = 0xe0
    private const val CPU_TYPE_X86 = 7
    private const val CPU_TYPE_X86_64 = 0x01000007
    private const val CPU_TYPE_ARM = 12
    private const val CPU_TYPE_ARM64 = 0x0100000c
    private const val CSMAGIC_EMBEDDED_SIGNATURE = 0xfade0cc0U
    private const val CSMAGIC_BLOBWRAPPER = 0xfade0b01U
    private const val CSSLOT_CODEDIRECTORY = 0U
    private val CSSLOT_ALTERNATE_CODEDIRECTORIES = 0x1000U..0x1005U
    private const val CSSLOT_SIGNATURESLOT = 0x10000U
    private const val MAX_LOAD_COMMANDS = 8_192
    private const val MAX_SYMBOLS = 2_000_000
    /** Full counts are retained; samples are bounded so one symbol-rich image cannot dominate DB/API memory. */
    private const val MAX_REPORTED_ITEMS = 500
    private const val MAX_DEBUG_SYMBOLS_PER_IMAGE = 250_000
    private const val MAX_SYMBOL_LENGTH = 1_024
}

internal object DerCertificates {
    fun extract(cms: ByteArray): List<SigningCertificateEvidence> {
        val out = LinkedHashMap<String, SigningCertificateEvidence>()
        walk(cms, 0, cms.size, 0) { start, end ->
            if (!looksLikeCertificate(cms, start, end)) return@walk
            val der = cms.copyOfRange(start, end)
            val fingerprint = Sha256.hex(der)
            val subject = subjectRange(der)
            out[fingerprint] = SigningCertificateEvidence(
                sha256 = fingerprint,
                commonName = subject?.let { attribute(der, it.first, it.second, OID_CN) },
                organization = subject?.let { attribute(der, it.first, it.second, OID_O) },
                organizationalUnit = subject?.let { attribute(der, it.first, it.second, OID_OU) },
            )
        }
        return out.values.take(16)
    }

    private fun walk(bytes: ByteArray, from: Int, to: Int, depth: Int, visitSequence: (Int, Int) -> Unit) {
        if (depth > 24) return
        var p = from
        while (p < to) {
            val node = node(bytes, p, to) ?: return
            if (node.tag == 0x30) visitSequence(p, node.end)
            if (node.tag and 0x20 != 0) walk(bytes, node.content, node.end, depth + 1, visitSequence)
            p = node.end
        }
    }

    private data class Node(val tag: Int, val content: Int, val end: Int)

    private fun node(bytes: ByteArray, at: Int, limit: Int): Node? {
        if (at + 2 > limit) return null
        val tag = bytes[at].toInt() and 0xff
        val first = bytes[at + 1].toInt() and 0xff
        var p = at + 2
        val length = if (first and 0x80 == 0) first else {
            val count = first and 0x7f
            if (count !in 1..4 || p + count > limit) return null
            var value = 0
            repeat(count) { value = (value shl 8) or (bytes[p++].toInt() and 0xff) }
            value
        }
        if (length < 0 || p + length > limit) return null
        return Node(tag, p, p + length)
    }

    private fun looksLikeCertificate(bytes: ByteArray, start: Int, end: Int): Boolean {
        val outer = node(bytes, start, end) ?: return false
        if (outer.tag != 0x30 || outer.end != end) return false
        val tbs = node(bytes, outer.content, end) ?: return false
        val algorithm = node(bytes, tbs.end, end) ?: return false
        val signature = node(bytes, algorithm.end, end) ?: return false
        return tbs.tag == 0x30 && algorithm.tag == 0x30 && signature.tag == 0x03 && signature.end == end
    }

    /** Locate subject in TBSCertificate; issuer has the same OIDs and must not be reported as signer. */
    private fun subjectRange(der: ByteArray): Pair<Int, Int>? {
        val outer = node(der, 0, der.size) ?: return null
        val tbs = node(der, outer.content, outer.end) ?: return null
        var p = tbs.content
        var child = node(der, p, tbs.end) ?: return null
        if (child.tag == 0xa0) { p = child.end; child = node(der, p, tbs.end) ?: return null }
        // serialNumber, signature, issuer, validity, then subject.
        repeat(4) { p = child.end; child = node(der, p, tbs.end) ?: return null }
        return child.content to child.end
    }

    private fun attribute(der: ByteArray, from: Int, to: Int, oid: ByteArray): String? {
        var i = from
        while (i + oid.size + 2 < to) {
            if (der[i].toInt() == 0x06 && der[i + 1].toInt() == oid.size &&
                oid.indices.all { der[i + 2 + it] == oid[it] }
            ) {
                val value = node(der, i + 2 + oid.size, to)
                if (value != null && value.tag in STRING_TAGS) {
                    return der.copyOfRange(value.content, value.end).decodeToString().take(256)
                }
            }
            i++
        }
        return null
    }

    private val STRING_TAGS = setOf(0x0c, 0x13, 0x14, 0x16, 0x1e)
    private val OID_CN = byteArrayOf(0x55, 0x04, 0x03)
    private val OID_O = byteArrayOf(0x55, 0x04, 0x0a)
    private val OID_OU = byteArrayOf(0x55, 0x04, 0x0b)
}

private fun u32le(b: ByteArray, p: Int): UInt =
    (b[p].toInt() and 0xff).toUInt() or ((b[p + 1].toInt() and 0xff).toUInt() shl 8) or
        ((b[p + 2].toInt() and 0xff).toUInt() shl 16) or ((b[p + 3].toInt() and 0xff).toUInt() shl 24)

private fun u16le(b: ByteArray, p: Int): Int =
    (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8)

private fun u32be(b: ByteArray, p: Int): UInt =
    ((b[p].toInt() and 0xff).toUInt() shl 24) or ((b[p + 1].toInt() and 0xff).toUInt() shl 16) or
        ((b[p + 2].toInt() and 0xff).toUInt() shl 8) or (b[p + 3].toInt() and 0xff).toUInt()

private fun u64le(b: ByteArray, p: Int): ULong {
    var value = 0UL
    for (i in 7 downTo 0) value = (value shl 8) or (b[p + i].toInt() and 0xff).toULong()
    return value
}

private fun u64be(b: ByteArray, p: Int): ULong {
    var value = 0UL
    for (i in 0..7) value = (value shl 8) or (b[p + i].toInt() and 0xff).toULong()
    return value
}

private fun ascii(b: ByteArray, p: Int, length: Int): String {
    var end = p
    val limit = (p + length).coerceAtMost(b.size)
    while (end < limit && b[end] != 0.toByte()) end++
    return b.copyOfRange(p, end).decodeToString()
}

private fun cString(b: ByteArray, p: Int, limit: Int): String? {
    if (p !in b.indices || p >= limit) return null
    var end = p
    while (end < limit && end < b.size && b[end] != 0.toByte()) end++
    return if (end > p) b.copyOfRange(p, end).decodeToString() else null
}

private fun formatUuid(b: ByteArray, p: Int): String {
    val hex = buildString(32) { repeat(16) { append((b[p + it].toInt() and 0xff).toString(16).padStart(2, '0')) } }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
}
