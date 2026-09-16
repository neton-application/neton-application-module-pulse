package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import neton.security.digest.Sha256
import neton.core.http.BadRequestException

class IosArtifactAnalyzerTest {

    @Test
    fun machOReportsSwiftCppCImportsTextAndLinkedLibraries() {
        val fixture = machOFixture()
        val image = MachOAnalyzer.analyze("Payload/Test.app/Test", fixture.bytes).single()

        assertEquals("arm64", image.architecture)
        assertEquals("00112233-4455-6677-8899-aabbccddeeff", image.imageUuid)
        assertEquals(Sha256.hex(fixture.text), image.textSha256)
        assertEquals(fixture.text.size.toLong(), image.textSize)
        assertTrue(image.hasSwiftMetadata)
        assertEquals(listOf("_\$s4Test3fooyyF"), image.swiftSymbols)
        assertEquals(listOf("__Z3foov"), image.cppSymbols)
        assertEquals(listOf("_plain_c"), image.cSymbols)
        assertEquals(listOf("_malloc"), image.importedSymbols)
        assertEquals(listOf("/usr/lib/libSystem.B.dylib"), image.linkedLibraries)
        assertEquals("unsigned", image.signatureStatus)
        assertFalse(image.symbolsTruncated)
    }

    @Test
    fun nonMachOIsIgnored() {
        assertFalse(MachOAnalyzer.hasMachOMagic("PNG resource".encodeToByteArray()))
        assertTrue(MachOAnalyzer.analyze("asset.png", "PNG resource".encodeToByteArray()).isEmpty())
    }

    @Test
    fun fatMachOReportsEveryArchitectureSlice() {
        val thin = machOFixture().bytes
        val offset = 8 + 20
        val fat = ByteArray(offset + thin.size)
        fat.u32be(0, 0xcafebabeU)
        fat.u32be(4, 1U)
        fat.u32be(8, 0x0100000cU)
        fat.u32be(12, 0U)
        fat.u32be(16, offset.toUInt())
        fat.u32be(20, thin.size.toUInt())
        thin.copyInto(fat, offset)
        assertEquals("arm64", MachOAnalyzer.analyze("fat.framework/fat", fat).single().architecture)
    }

    @Test
    fun zipTraversalAndSymlinkAreRejectedBeforeExtraction() {
        assertFailsWith<BadRequestException> {
            IosArtifactAnalyzer.validateZipStructure(centralDirectoryZip("Payload/../escape"))
        }
        assertFailsWith<BadRequestException> {
            IosArtifactAnalyzer.validateZipStructure(
                centralDirectoryZip("Payload/link", madeBy = 3 shl 8, external = 0xa000U shl 16),
            )
        }
        IosArtifactAnalyzer.validateZipStructure(centralDirectoryZip("Payload/App.app/App"))
    }

    @Test
    fun certificateEvidenceUsesSubjectRatherThanIssuer() {
        val certificate = certificateFixture()
        val evidence = DerCertificates.extract(certificate).single()
        assertEquals("Distribution Signer", evidence.commonName)
        assertEquals(Sha256.hex(certificate), evidence.sha256)
    }

    @Test
    fun machOCodeSignatureYieldsCertificateEvidence() {
        val image = MachOAnalyzer.analyze("Payload/Signed.app/Signed", machOFixture(certificateFixture()).bytes).single()
        assertEquals("signed", image.signatureStatus)
        assertEquals("Distribution Signer", image.signingCertificates.single().commonName)
    }

    private data class Fixture(val bytes: ByteArray, val text: ByteArray)

    private fun machOFixture(certificate: ByteArray? = null): Fixture {
        val commandsSize = 152 + 24 + 56 + 24 + if (certificate != null) 16 else 0
        val textOffset = 32 + commandsSize
        val text = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x7f)
        val symOffset = textOffset + text.size
        val names = listOf("_\$s4Test3fooyyF", "__Z3foov", "_plain_c", "_malloc")
        val stringTable = ArrayList<Byte>().apply { add(0) }
        val indexes = names.map { name ->
            val start = stringTable.size
            name.encodeToByteArray().forEach(stringTable::add)
            stringTable.add(0)
            start
        }
        val strOffset = symOffset + names.size * 16
        val signature = certificate?.let(::codeSignature)
        val signatureOffset = strOffset + stringTable.size
        val out = ByteArray(signatureOffset + (signature?.size ?: 0))

        // mach_header_64
        out.u32(0, 0xfeedfacfU)
        out.u32(4, 0x0100000cU) // CPU_TYPE_ARM64
        out.u32(8, 0U)
        out.u32(12, 2U) // MH_EXECUTE
        out.u32(16, (4 + if (certificate != null) 1 else 0).toUInt())
        out.u32(20, commandsSize.toUInt())

        var p = 32
        // LC_SEGMENT_64 with one __swift5_* section. The __TEXT range is deliberately small so
        // the expected fingerprint proves we hash the segment bytes, not the complete file.
        out.u32(p, 0x19U); out.u32(p + 4, 152U)
        out.ascii(p + 8, 16, "__TEXT")
        out.u64(p + 40, textOffset.toULong())
        out.u64(p + 48, text.size.toULong())
        out.u32(p + 64, 1U)
        out.ascii(p + 72, 16, "__swift5_types")
        out.ascii(p + 88, 16, "__TEXT")
        p += 152

        out.u32(p, 0x1bU); out.u32(p + 4, 24U)
        byteArrayOf(
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            0x88.toByte(), 0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(),
            0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte(),
        ).copyInto(out, p + 8)
        p += 24

        out.u32(p, 0x0cU); out.u32(p + 4, 56U); out.u32(p + 8, 24U)
        out.ascii(p + 24, 32, "/usr/lib/libSystem.B.dylib")
        p += 56

        out.u32(p, 0x02U); out.u32(p + 4, 24U)
        out.u32(p + 8, symOffset.toUInt()); out.u32(p + 12, names.size.toUInt())
        out.u32(p + 16, strOffset.toUInt()); out.u32(p + 20, stringTable.size.toUInt())
        p += 24

        if (signature != null) {
            out.u32(p, 0x1dU); out.u32(p + 4, 16U)
            out.u32(p + 8, signatureOffset.toUInt()); out.u32(p + 12, signature.size.toUInt())
        }

        text.copyInto(out, textOffset)
        names.indices.forEach { i ->
            val entry = symOffset + i * 16
            out.u32(entry, indexes[i].toUInt())
            out[entry + 4] = if (i == 3) 0x01 else 0x0f
            out[entry + 5] = if (i == 3) 0 else 1
        }
        stringTable.forEachIndexed { i, byte -> out[strOffset + i] = byte }
        signature?.copyInto(out, signatureOffset)
        return Fixture(out, text)
    }

    private fun codeSignature(certificate: ByteArray): ByteArray {
        val codeDirectoryOffset = 28
        val wrapperOffset = codeDirectoryOffset + 8
        val total = wrapperOffset + 8 + certificate.size
        return ByteArray(total).also { out ->
            out.u32be(0, 0xfade0cc0U); out.u32be(4, total.toUInt()); out.u32be(8, 2U)
            out.u32be(12, 0U); out.u32be(16, codeDirectoryOffset.toUInt())
            out.u32be(20, 0x10000U); out.u32be(24, wrapperOffset.toUInt())
            out.u32be(codeDirectoryOffset, 0xfade0c02U); out.u32be(codeDirectoryOffset + 4, 8U)
            out.u32be(wrapperOffset, 0xfade0b01U); out.u32be(wrapperOffset + 4, (8 + certificate.size).toUInt())
            certificate.copyInto(out, wrapperOffset + 8)
        }
    }

    private fun certificateFixture(): ByteArray = sequence(
        sequence(
            tlv(0x02, byteArrayOf(1)), sequence(), distinguishedName("Issuer"), sequence(),
            distinguishedName("Distribution Signer"), sequence(),
        ),
        sequence(), tlv(0x03, byteArrayOf(0)),
    )

    private fun centralDirectoryZip(name: String, madeBy: Int = 0, external: UInt = 0U): ByteArray {
        val nameBytes = name.encodeToByteArray()
        val central = ByteArray(46 + nameBytes.size)
        central.u32(0, 0x02014b50U)
        central.u16(4, madeBy)
        central.u32(24, 1U)
        central.u16(28, nameBytes.size)
        central.u32(38, external)
        nameBytes.copyInto(central, 46)
        val eocd = ByteArray(22)
        eocd.u32(0, 0x06054b50U)
        eocd.u16(8, 1); eocd.u16(10, 1)
        eocd.u32(12, central.size.toUInt())
        eocd.u32(16, 0U)
        return central + eocd
    }

    private fun distinguishedName(commonName: String): ByteArray =
        sequence(tlv(0x31, sequence(tlv(0x06, byteArrayOf(0x55, 0x04, 0x03)), tlv(0x0c, commonName.encodeToByteArray()))))

    private fun sequence(vararg children: ByteArray): ByteArray = tlv(0x30, children.fold(ByteArray(0), ByteArray::plus))

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        require(value.size < 128)
        return byteArrayOf(tag.toByte(), value.size.toByte()) + value
    }

    private fun ByteArray.u32(at: Int, value: UInt) {
        repeat(4) { this[at + it] = ((value shr (it * 8)) and 0xffU).toByte() }
    }

    private fun ByteArray.u16(at: Int, value: Int) {
        this[at] = (value and 0xff).toByte()
        this[at + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun ByteArray.u64(at: Int, value: ULong) {
        repeat(8) { this[at + it] = ((value shr (it * 8)) and 0xffUL).toByte() }
    }

    private fun ByteArray.u32be(at: Int, value: UInt) {
        repeat(4) { this[at + it] = ((value shr ((3 - it) * 8)) and 0xffU).toByte() }
    }

    private fun ByteArray.ascii(at: Int, width: Int, value: String) {
        value.encodeToByteArray().copyInto(this, at, endIndex = minOf(width, value.length))
    }
}
