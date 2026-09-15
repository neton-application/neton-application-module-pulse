package ingest

import kotlin.test.Test
import kotlin.test.assertEquals

class PulseBizTypeTest {
    @Test
    fun serverMirrorKeepsTheSevenPermanentWireValues() {
        assertEquals(
            (1..7).toList(),
            listOf(
                PulseBizType.EVENT_BATCH_UPLOAD,
                PulseBizType.APP_UPDATE_CHECK,
                PulseBizType.SESSION_REGISTER,
                PulseBizType.CRASH_BATCH_UPLOAD,
                PulseBizType.CONFIG_PULL,
                PulseBizType.HEARTBEAT,
                PulseBizType.CONTROL_RPC,
            ),
        )
        assertEquals(0, PulseBizType.RESERVED)
    }
}
