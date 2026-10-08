package logic

/**
 * 三段式语义版本（major.minor.patch[-prerelease]）比较。
 *
 * 更新判断改用版本名（如 `1.0.2` vs `1.0.3`）而非整数构建号。原模型注释担心「客户端和服务端
 * 两套比较规则会漂移」——在 Pulse 的拓扑下不成立:比较**只在服务端这一处**做,客户端只上报自己
 * 的版本名(connect 身份里的 appVersion),不含任何比较逻辑。所以只有这一个实现,不存在第二套。
 *
 * 规则遵循 semver.org:
 *   - 先比 major、minor、patch(数值);
 *   - 预发布版本(带 `-xxx`)**低于**同号正式版(`1.0.0-beta` < `1.0.0`);
 *   - 两个预发布比点分标识符:纯数字按数值、含字母按字典序、数字段 < 字母段、字段少的更小。
 * 解析失败(非三段式)返回 null,调用方据此回退到构建号比较。
 */
internal data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val pre: List<String>,
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        (major - other.major).let { if (it != 0) return it.coerceIn(-1, 1) }
        (minor - other.minor).let { if (it != 0) return it.coerceIn(-1, 1) }
        (patch - other.patch).let { if (it != 0) return it.coerceIn(-1, 1) }
        // 无预发布 > 有预发布。
        if (pre.isEmpty() && other.pre.isEmpty()) return 0
        if (pre.isEmpty()) return 1
        if (other.pre.isEmpty()) return -1
        val n = minOf(pre.size, other.pre.size)
        for (i in 0 until n) {
            val a = pre[i]
            val b = other.pre[i]
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1 // 数字段 < 字母段
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c.coerceIn(-1, 1)
        }
        return pre.size.compareTo(other.pre.size).coerceIn(-1, 1)
    }

    companion object {
        /** 解析 `X.Y.Z` 或 `X.Y.Z-pre.a.b`;前缀允许 `v`。非三段式返回 null。 */
        fun parseOrNull(raw: String?): SemVer? {
            val s = raw?.trim()?.removePrefix("v")?.ifEmpty { null } ?: return null
            val dash = s.indexOf('-')
            val core = if (dash >= 0) s.substring(0, dash) else s
            val pre = if (dash >= 0) s.substring(dash + 1).split('.').filter { it.isNotEmpty() } else emptyList()
            val parts = core.split('.')
            if (parts.size != 3) return null
            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts[1].toIntOrNull() ?: return null
            val patch = parts[2].toIntOrNull() ?: return null
            if (major < 0 || minor < 0 || patch < 0) return null
            return SemVer(major, minor, patch, pre)
        }
    }
}
