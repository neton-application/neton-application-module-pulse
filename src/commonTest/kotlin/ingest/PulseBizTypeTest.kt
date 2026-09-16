package ingest

import kotlin.test.Test
import kotlin.test.assertEquals

class PulseBizTypeTest {
    @Test
    fun serverMirrorKeepsTheSevenPermanentWireValues() {
        assertEquals(
            (1..7).toList(),
            listOf(
                PulseBizType.CLIENT_CONNECT,
                PulseBizType.CLIENT_HEARTBEAT,
                PulseBizType.CLIENT_CONFIG_PULL,
                PulseBizType.CLIENT_CRASH_BATCH_UPLOAD,
                PulseBizType.CLIENT_EVENT_BATCH_UPLOAD,
                PulseBizType.CLIENT_APP_UPDATE_CHECK,
                PulseBizType.BIDIRECTIONAL_CONTROL_RPC,
            ),
        )
        assertEquals(0, PulseBizType.RESERVED)
    }
}
