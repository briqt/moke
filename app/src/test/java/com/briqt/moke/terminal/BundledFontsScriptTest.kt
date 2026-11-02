package com.briqt.moke.terminal

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BundledFontsScriptTest {
    @get:Rule val temporary = TemporaryFolder()
    private val names = listOf("jetbrains_mono.ttf", "noto_sans_symbols2.ttf", "symbols_nerd_font.ttf")

    private fun fixture(): File {
        val root = temporary.newFolder()
        val sourceRoot = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        File(root, "scripts").mkdirs()
        File(sourceRoot, "scripts/verify-bundled-fonts.sh").copyTo(File(root, "scripts/verify-bundled-fonts.sh"))
        File(root, "app/src/main/res/font").mkdirs()
        // Byte-identity fixtures, not substitutes for actual font loading or pixel tests.
        names.forEach { File(root, "app/src/main/res/font/$it").writeText("font-content-$it") }
        return root
    }

    private fun run(root: File, vararg args: String): Int {
        val process = ProcessBuilder(listOf("bash", File(root, "scripts/verify-bundled-fonts.sh").path) + args)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val status = process.waitFor()
        if (status != 0) System.out.print(output)
        return status
    }

    private fun apk(root: File, entries: List<Pair<String, ByteArray>>): File =
        File(root, "fixture.apk").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }

    private fun entries(root: File): List<Pair<String, ByteArray>> = names.mapIndexed { index, name ->
        "res/short$index.ttf" to File(root, "app/src/main/res/font/$name").readBytes()
    }

    @Test fun validSourceInventoryPasses() {
        assertEquals(0, run(fixture(), "check"))
    }

    @Test fun missingSourceFontIsRejected() {
        val root = fixture()
        File(root, "app/src/main/res/font/${names.last()}").delete()
        assertEquals(1, run(root, "check"))
    }

    @Test fun staleLargeFontIsRejectedEvenWhenItIsIgnoredByGit() {
        val root = fixture()
        File(root, "app/src/main/res/font/maple_mono.ttf").writeText("stale")
        assertEquals(1, run(root, "check"))
    }

    @Test fun renamedApkFontResourcesPassByTheirContents() {
        val root = fixture()
        assertEquals(0, run(root, "apk", apk(root, entries(root)).path))
    }

    @Test fun missingAndExtraApkFontsAreRejected() {
        val root = fixture()
        val expected = entries(root)
        assertEquals(1, run(root, "apk", apk(root, expected.dropLast(1)).path))
        assertEquals(1, run(root, "apk", apk(root, expected + ("res/extra.otf" to byteArrayOf(1))).path))
    }

    @Test fun sameCountWithWrongFontContentIsRejected() {
        val root = fixture()
        val wrong = entries(root).toMutableList()
        wrong[0] = "res/wrong.ttf" to byteArrayOf(1, 2, 3)
        assertEquals(1, run(root, "apk", apk(root, wrong).path))
    }

    @Test fun duplicatedFontCannotReplaceAnotherExpectedFont() {
        val root = fixture()
        val wrong = entries(root).toMutableList()
        wrong[0] = "res/duplicate.ttf" to wrong[1].second
        assertEquals(1, run(root, "apk", apk(root, wrong).path))
    }

    @Test fun unreadableArchiveAndInvalidArgumentsAreRejected() {
        val root = fixture()
        val invalid = File(root, "invalid.apk").apply { writeText("not a ZIP") }
        assertEquals(1, run(root, "apk", invalid.path))
        assertEquals(2, run(root, "apk"))
    }
}
