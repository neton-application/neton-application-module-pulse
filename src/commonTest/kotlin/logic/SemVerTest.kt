package logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 更新判断的新旧比较落在这里——比错了要么漏推更新、要么对着老用户反复弹。 */
class SemVerTest {

    private fun v(s: String) = SemVer.parseOrNull(s)!!

    @Test
    fun orders_three_parts_numerically() {
        assertTrue(v("1.0.2") < v("1.0.3"))
        assertTrue(v("1.0.10") > v("1.0.9")) // 数值比,不是字典序
        assertTrue(v("1.2.0") > v("1.1.9"))
        assertTrue(v("2.0.0") > v("1.9.9"))
        assertEquals(0, v("1.0.2").compareTo(v("1.0.2")))
    }

    @Test
    fun prerelease_is_lower_than_release() {
        assertTrue(v("1.0.0-beta.22") < v("1.0.0"))
        assertTrue(v("1.0.0-beta.1") < v("1.0.0-beta.2"))
        assertTrue(v("1.0.0-alpha") < v("1.0.0-beta"))
        assertTrue(v("1.0.0-beta.2") < v("1.0.0-beta.10")) // 数字段按数值
    }

    @Test
    fun tolerates_v_prefix() {
        assertEquals(0, v("v1.0.2").compareTo(v("1.0.2")))
    }

    @Test
    fun rejects_non_three_part() {
        assertNull(SemVer.parseOrNull(null))
        assertNull(SemVer.parseOrNull(""))
        assertNull(SemVer.parseOrNull("1.0"))
        assertNull(SemVer.parseOrNull("20261008"))
        assertNull(SemVer.parseOrNull("1.0.x"))
    }
}
