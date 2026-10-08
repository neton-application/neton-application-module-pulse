package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R8 反混淆决定 Android 崩溃栈是否可读——映射错了,堆栈会指向错误的类/方法,比乱码更坏。
 * 覆盖:类名+方法名还原、带行号的成员行、认不出的帧原样保留、空/非法 mapping。
 */
class R8RetraceTest {

    private val mapping = """
        com.netonstream.weey.ChatActivity -> a.b.c:
            void onCreate(android.os.Bundle) -> a
            12:34:void loadMessages(long):56:78 -> b
            int count -> c
        com.netonstream.weey.Sdk${'$'}Worker -> a.b.d:
            void run() -> a
    """.trimIndent()

    @Test
    fun retraces_class_and_method_names() {
        val m = R8Retrace.parse(mapping)
        assertFalse(m.isEmpty())
        assertEquals(2, m.classCount())

        val stack = """
            java.lang.NullPointerException
            ${'\t'}at a.b.c.b(SourceFile:57)
            ${'\t'}at a.b.c.a(SourceFile:12)
            ${'\t'}at a.b.d.a(Unknown Source:3)
        """.trimIndent()

        val out = m.retrace(stack)
        assertTrue(out.contains("com.netonstream.weey.ChatActivity.loadMessages"), out)
        assertTrue(out.contains("com.netonstream.weey.ChatActivity.onCreate"), out)
        assertTrue(out.contains("com.netonstream.weey.Sdk\$Worker.run"), out)
        // 源文件/行号保留。
        assertTrue(out.contains("(SourceFile:57)"), out)
    }

    @Test
    fun leaves_unknown_frames_untouched() {
        val m = R8Retrace.parse(mapping)
        val stack = "\tat x.y.z.unknown(SourceFile:1)"
        assertEquals(stack, m.retrace(stack))
    }

    @Test
    fun empty_mapping_parses_empty() {
        assertTrue(R8Retrace.parse("").isEmpty())
        assertTrue(R8Retrace.parse("# just a comment\n\n").isEmpty())
    }
}
