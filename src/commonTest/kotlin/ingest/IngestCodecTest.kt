package ingest

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire is the contract between the SDK and this server, and the two ship independently, so
 * both shapes have to keep working: the current `{identity, events}` envelope and the bare event
 * array that predates it.
 */
class IngestCodecTest {

    private val envelope = """
        {"identity":{"projectId":"p1","installationId":"i1","deviceId":"d1","userId":"u1"},
         "events":[{"id":"e1","timestamp":123,"sessionId":"s1","kind":"Runtime",
                    "name":"clipboard_read","source":{"module":"DemoSDK","imageUuid":"00112233-4455-6677-8899-aabbccddeeff"},"attributes":{}}]}
    """.trimIndent()

    private val bareArray = """
        [{"id":"e1","timestamp":123,"sessionId":"s1","kind":"Analytics",
          "name":"purchase","source":{},"attributes":{}}]
    """.trimIndent()

    @Test
    fun theEnvelopeFlattensIdentityOntoEveryRow() {
        val rows = IngestCodec.toRows(IngestCodec.decodeBatch(envelope.encodeToByteArray()))
        val row = rows.single()
        assertEquals("p1", row.projectId)
        assertEquals("d1", row.deviceId)
        assertEquals("i1", row.installationId)
        assertEquals("u1", row.userId)
        assertEquals("Runtime", row.kind)
        assertEquals("DemoSDK", row.sourceModule)
        assertEquals("00112233-4455-6677-8899-aabbccddeeff", row.sourceImageUuid)
    }

    @Test
    fun aBareArrayFromAnOlderClientStillIngests() {
        // No identity available, so those columns stay null — the events must still land rather
        // than the batch being rejected because an old SDK is in the field.
        val rows = IngestCodec.toRows(IngestCodec.decodeBatch(bareArray.encodeToByteArray()))
        val row = rows.single()
        assertEquals("purchase", row.name)
        assertNull(row.deviceId)
        assertNull(row.projectId)
    }

    @Test
    fun leadingWhitespaceDoesNotConfuseShapeDetection() {
        val padded = "  \n\t" + bareArray
        assertEquals(1, IngestCodec.toRows(IngestCodec.decodeBatch(padded.encodeToByteArray())).size)
    }

    @Test
    fun anUnknownFieldIsIgnoredSoEitherSideCanAddOne() {
        val withExtra = """{"identity":{"projectId":"p","deviceId":"d","futureField":1},
            "events":[{"id":"e","timestamp":1,"sessionId":"s","kind":"Log","name":"n",
                       "source":{},"attributes":{},"somethingNew":true}],"alsoNew":"x"}"""
        val row = IngestCodec.toRows(IngestCodec.decodeBatch(withExtra.encodeToByteArray())).single()
        assertEquals("d", row.deviceId)
        assertEquals("n", row.name)
    }

    @Test
    fun malformedImageUuidCannotPoisonTheDatabaseBatch() {
        val payload = envelope.replace("00112233-4455-6677-8899-aabbccddeeff", "not-a-uuid")
        val row = IngestCodec.toRows(IngestCodec.decodeBatch(payload.encodeToByteArray())).single()
        assertNull(row.sourceImageUuid)
    }

    @Test
    fun uploadAcknowledgementUsesTheCommonResponseEnvelope() {
        val encoded = IngestCodec.encodeUploadResponse(PulseWireResponse(code = 0, data = true))
            .decodeToString()
        val json = IngestCodec.json.parseToJsonElement(encoded).jsonObject
        assertEquals(0, json.getValue("code").jsonPrimitive.int)
        assertTrue(json.getValue("msg") is kotlinx.serialization.json.JsonNull)
        assertTrue(json.getValue("data").jsonPrimitive.boolean)
    }

    @Test
    fun updateResponseSupportsNewEnvelopeAndExistingClients() {
        val update = logic.UpdateCheckResult(
            action = logic.UpdateAction.OPTIONAL,
            latestVersionName = "1.1.0",
            latestBuildNumber = 11,
        )
        val json = IngestCodec.json.parseToJsonElement(
            IngestCodec.encodeUpdateResponse(PulseWireResponse(code = 0, data = update)).decodeToString(),
        ).jsonObject
        assertEquals(0, json.getValue("code").jsonPrimitive.int)
        assertEquals("optional", json.getValue("data").jsonObject.getValue("action").jsonPrimitive.content)
        assertEquals("optional", json.getValue("action").jsonPrimitive.content)
        assertEquals("1.1.0", json.getValue("latestVersionName").jsonPrimitive.content)
    }

    @Test
    fun registrationBindsTheConnectionToTheBatchIdentity() {
        val request = IngestCodec.decodeSessionRegistration(
            """{"protocolVersion":1,"appId":"p1","deviceId":"d1","installationId":"i1",
                "platform":"ios","deviceType":"iphone","packageName":"com.example.app",
                "appVersion":"1.0.0","buildNumber":1,"sdkVersion":"1.0.0",
                "capabilities":["batch_upload"]}""".encodeToByteArray(),
        )
        val registered = RegisteredConnection(request)

        assertNull(
            registered.batchIdentityRejection(
                IngestIdentity("p1", "i1", "d1", platform = "ios", packageName = "com.example.app"),
            ),
        )
        assertEquals(
            "batch device ID does not match registered connection",
            registered.batchIdentityRejection(
                IngestIdentity("p1", "i1", "other", platform = "ios", packageName = "com.example.app"),
            ),
        )
    }

    @Test
    fun registrationResponseUsesTheCommonEnvelope() {
        val encoded = IngestCodec.encodeSessionRegistrationResponse(
            PulseWireResponse(
                code = 0,
                data = SessionRegisterResult(true, 1, "connection-1", 1234),
            ),
        ).decodeToString()
        val json = IngestCodec.json.parseToJsonElement(encoded).jsonObject
        assertEquals(0, json.getValue("code").jsonPrimitive.int)
        assertTrue(json.getValue("data").jsonObject.getValue("registered").jsonPrimitive.boolean)
    }
}
