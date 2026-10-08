package logic

/**
 * R8 / ProGuard mapping.txt 反混淆。
 *
 * Android 的 Java/Kotlin 崩溃栈经 R8 混淆后类名、方法名都是 `a`、`b` 这种,只有 mapping.txt
 * 能把它们还原。这里做的是**符号名还原**（类名 + 方法名），覆盖 90% 的可读性需求;完整的
 * 行号/内联帧回溯（`lineStart:lineEnd:...:origStart:origEnd` 的逆映射与内联展开）是另一个量级
 * 的工作,刻意不在此做——地址变函数名已经让堆栈可读。
 *
 * mapping.txt 形如:
 * ```
 * com.original.Foo -> a.b.c:
 *     int someField -> a
 *     12:34:void doWork(int):56:78 -> d
 *     void other() -> e
 * ```
 * 非缩进、以 `:` 结尾且含 ` -> ` 的行是类映射(左=原始,右=混淆);其后缩进行是成员映射。
 * 崩溃帧形如 `at a.b.c.d(SourceFile:3)` —— 把混淆的 `a.b.c`→原始类、`d`→原始方法。
 */
internal object R8Retrace {

    internal class ClassMapping(
        val originalName: String,
        /** 混淆方法名 → 原始方法名（可能多个重载/内联,取第一个,足够定位）。 */
        val methods: MutableMap<String, String> = mutableMapOf(),
    )

    class Mapping internal constructor(
        /** 混淆类名 → 映射。 */
        private val byObf: Map<String, ClassMapping>,
    ) {
        fun isEmpty() = byObf.isEmpty()

        fun classCount() = byObf.size

        /** 反混淆一帧的 `类.方法`;无法映射时原样返回。 */
        private fun frame(obfClass: String, obfMethod: String): Pair<String, String> {
            val cls = byObf[obfClass] ?: return obfClass to obfMethod
            val method = cls.methods[obfMethod] ?: obfMethod
            return cls.originalName to method
        }

        /** 反混淆整段文本堆栈;逐行处理,认不出的行原样保留。 */
        fun retrace(stack: String): String =
            stack.lineSequence().joinToString("\n") { line -> retraceLine(line) }

        private fun retraceLine(line: String): String {
            val m = FRAME.find(line) ?: return line
            val fqcnMethod = m.groupValues[1] // a.b.c.d
            val dot = fqcnMethod.lastIndexOf('.')
            if (dot <= 0) return line
            val obfClass = fqcnMethod.substring(0, dot)
            val obfMethod = fqcnMethod.substring(dot + 1)
            val (cls, method) = frame(obfClass, obfMethod)
            if (cls == obfClass && method == obfMethod) return line
            return line.replaceRange(m.groups[1]!!.range, "$cls.$method")
        }

        private companion object {
            // `at <fqcn.method>(...)`：抓到 `fqcn.method`，保留括号里的源文件/行号不动。
            val FRAME = Regex("""\bat\s+([\w$.]+)\(""")
        }
    }

    fun parse(text: String): Mapping {
        val byObf = HashMap<String, ClassMapping>()
        var current: ClassMapping? = null
        var currentObf: String? = null
        for (raw in text.lineSequence()) {
            if (raw.isBlank() || raw.startsWith("#")) continue
            val indented = raw.first() == ' ' || raw.first() == '\t'
            val arrow = raw.indexOf(" -> ")
            if (arrow < 0) continue
            if (!indented && raw.trimEnd().endsWith(":")) {
                // 类映射行: original -> obf:
                val original = raw.substring(0, arrow).trim()
                val obf = raw.substring(arrow + 4).trim().removeSuffix(":").trim()
                if (original.isNotEmpty() && obf.isNotEmpty()) {
                    val cm = ClassMapping(original)
                    byObf[obf] = cm
                    current = cm
                    currentObf = obf
                }
            } else if (indented && current != null) {
                // 成员映射行: [line:line:]ret name(args)[:line:line] -> obfName
                val obfName = raw.substring(arrow + 4).trim()
                val left = raw.substring(0, arrow).trim()
                val originalMethod = parseMemberOriginalName(left) ?: continue
                // 字段也走这，但字段名不会出现在栈帧里，多存无害;重载只留第一个。
                if (!current.methods.containsKey(obfName)) current.methods[obfName] = originalMethod
            }
        }
        return Mapping(byObf)
    }

    /**
     * 从成员左侧取原始**名字**。左侧可能是:
     *   `void doWork(int)`                （方法）
     *   `12:34:void doWork(int):56:78`    （带行号的方法）
     *   `int someField`                   （字段）
     * 规则:去掉前导的 `lineStart:lineEnd:`,取签名里 `(` 之前的最后一个空格分隔 token;
     * 没有 `(` 则是字段,取最后一个 token。
     */
    private fun parseMemberOriginalName(leftRaw: String): String? {
        var left = leftRaw
        // 剥掉前导行号 `12:34:`。
        while (true) {
            val m = LEAD_LINES.find(left) ?: break
            left = left.substring(m.value.length)
        }
        val paren = left.indexOf('(')
        val sig = if (paren >= 0) left.substring(0, paren) else left
        val name = sig.trim().substringAfterLast(' ').trim()
        return name.ifEmpty { null }
    }

    private val LEAD_LINES = Regex("""^\d+:\d+:""")
}
