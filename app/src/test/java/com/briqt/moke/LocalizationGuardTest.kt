package com.briqt.moke

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 闸门：**产品代码里不得出现面向用户的中文字面量**。
 *
 * issue #1 就是这么漏的——`[会话结束]` 硬编码在 vendored 的 `TerminalSession` 里，
 * 英文界面照样弹中文。当时人工扫过一遍没扫到（用的 grep 在那台机器上会静默返回空），
 * 所以这件事不能再靠人肉核对：让测试每次构建都自己查一遍。
 *
 * 中文文案一律进 `values-zh/strings.xml`，代码里只留资源 id。
 */
class LocalizationGuardTest {

    /** 被扫描的产品源码目录（不含测试）。 */
    private val sourceRoots = listOf(
        "app/src/main/java",
        "terminal-emulator/src/main/java",
        "terminal-view/src/main/java",
    )

    /**
     * 允许出现中文的地方，每条都得说明为什么。
     *
     * 判定作用在**整行**上：命中就跳过该行。
     */
    private val allowed = listOf(
        // 配色 / 字体目录里的中文显示名：与英文名成对存放，渲染时按当前语言二选一，
        // 本身就是"中文那一份"，不是漏翻。FontCatalog 用具名参数 `nameZh =`；
        // TerminalThemes 是位置参数，只能整文件放行——它是纯颜色表，没有别的文案。
        Allow("nameZh", "字体目录的中文显示名，按语言二选一"),
        Allow("TerminalThemes.kt", "配色方案的中文显示名（第 3 个位置参数），按语言二选一"),
        // 外观预览的样张内容：故意含中日韩字形，用来演示回退字体的渲染效果。
        Allow("PreviewTransport.kt", "外观预览样张，故意含 CJK 以演示字体回退"),
    )

    private data class Allow(val marker: String, val why: String)

    private val han = Regex("[\\u4e00-\\u9fff]")
    private val stringLiteral = Regex(""""((?:[^"\\]|\\.)*)"""")

    @Test
    fun `产品代码里没有面向用户的中文字面量`() {
        val root = repoRoot()
        val offenders = mutableListOf<String>()

        sourceRoots.forEach { rel ->
            val dir = File(root, rel)
            assertTrue("源码目录不存在：$rel（测试的路径推断坏了，先修测试）", dir.isDirectory)
            dir.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { file ->
                    val path = file.relativeTo(root).path
                    file.readLines().forEachIndexed { i, line ->
                        if (isComment(line)) return@forEachIndexed
                        if (allowed.any { it.marker in line || it.marker in path }) return@forEachIndexed
                        stringLiteral.findAll(stripTrailingComment(line))
                            .map { it.groupValues[1] }
                            .filter { han.containsMatchIn(it) }
                            .forEach { lit ->
                                offenders += "$path:${i + 1}  \"$lit\""
                            }
                    }
                }
        }

        assertTrue(
            buildString {
                appendLine("发现 ${offenders.size} 处中文字面量——请改成 strings.xml 资源：")
                offenders.forEach { appendLine("  $it") }
                appendLine("确属故意（如中英成对的显示名）则在本测试的 allowed 里加一条并写明理由。")
            },
            offenders.isEmpty(),
        )
    }

    private fun isComment(line: String): Boolean =
        line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /** 去掉行尾 `//` 注释，但不要被字符串里的 `//`（如 URL）骗到。 */
    private fun stripTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && inString -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && i + 1 < line.length && line[i + 1] == '/' -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    /** 单测的工作目录是模块目录，往上找到含 settings.gradle.kts 的那层即仓库根。 */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到仓库根（settings.gradle.kts）")
    }
}
