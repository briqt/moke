package com.termux.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.briqt.moke.BuildConfig
import com.briqt.moke.terminal.FontRepository
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Software Canvas pixels and real application fonts, not screenshots or a layout-helper oracle.
 * No Activity, transport, font download or filesystem mutation is involved. These tests do not
 * substitute for window-attached preview/session, hardware rendering or visual acceptance tests.
 */
@RunWith(AndroidJUnit4::class)
class TerminalRendererTest {
    private val context: Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val fonts = FontRepository(context)
    private val images = mutableListOf<Bitmap>()

    @After
    fun recycleImages() {
        images.forEach { it.recycle() }
        images.clear()
    }

    @Test
    fun fullBlocksFillTheGridForBothBundledPrimaryFonts() {
        checkFullBlocks(faces(null), listOf(1f), listOf(0f), listOf(13, 37, 57))
    }

    @Test
    fun fullBlocksFillTheGridWithAnAlreadyInstalledMapleFallback() {
        val bundled = BuildConfig.BUNDLE_MAPLE && context.resources.getIdentifier(
            "maple_mono", "font", context.packageName,
        ) != 0
        assumeTrue("Maple is neither bundled nor already installed; no download is allowed",
            bundled || fonts.fileFor("maple_mono").isFile)
        checkFullBlocks(faces("maple_mono"), listOf(0.7f, 1f, 1.3f),
            listOf(-0.15f, 0.0375f, 0.15f), listOf(31))
    }

    @Test
    fun fullBlocksHaveNoSeamsAtLegalSpacingExtremes() {
        checkFullBlocks(faces(null), listOf(0.7f, 1.3f),
            listOf(-0.15f, 0.0375f, 0.15f), listOf(31))
    }

    private fun checkFullBlocks(
        faces: List<Pair<String, Typeface>>,
        lines: List<Float>,
        spacing: List<Float>,
        sizes: List<Int>,
    ) {
        for ((name, face) in faces) for (size in sizes) for (line in lines) for (letter in spacing) {
            val f = Fixture(face, size, line, letter, columns = 17)
            repeat(f.rows) { f.put(it, 0, "█".repeat(f.columns)) }
            val image = f.draw()
            val area = f.area(0, 0, f.columns).apply {
                bottom = top + f.rows * f.renderer.mFontLineSpacing
                // Tight spacing may place the first grid edge above the View's viewport.
                intersect(0, 0, image.width, image.height)
            }
            assertSolid("$name size=$size line=$line letter=$letter", image, area, FG)
        }
    }

    @Test
    fun graphicsHaveNoSeamsAtFractionalCellWidthsAndTranslatedClips() {
        for (width in listOf(7.25f, 25.8f, 41.125f)) for (origin in listOf(0.25f, 0.3f, 0.375f)) {
            val height = 37
            val columns = 17
            val image = bitmap(ceil(origin + columns * width).toInt() + 2, 2 * height + 3)
            val canvas = Canvas(image)
            canvas.drawColor(BG)
            val graphics = TerminalGraphics(width, height)
            canvas.clipRect(origin, 0.375f, origin + columns * width, 0.375f + 2 * height)
            repeat(2) { row -> repeat(columns) { column ->
                assertTrue(graphics.draw(canvas, 0x2588, origin + column * width, 0.375f + row * height, FG))
            } }
            assertSolid("fractional width=$width origin=$origin", image,
                Rect(ceil(origin).toInt(), 1, floor(origin + columns * width).toInt(), 2 * height), FG)
        }
    }

    @Test
    fun halfBlocksQuadrantsAndEighthsKeepTheirCoverageSemantics() {
        val f = Fixture(faces(null).first().second, size = 43, columns = 24)
        // Bits are top-left, top-right, bottom-left, bottom-right, independently of rendering code.
        val quadrants = listOf('▀' to 12, '▄' to 3, '▌' to 10, '▐' to 5,
            '▖' to 2, '▗' to 1, '▘' to 8, '▙' to 11, '▚' to 9,
            '▛' to 14, '▜' to 13, '▝' to 4, '▞' to 6, '▟' to 7)
        f.put(0, 0, quadrants.joinToString("") { it.first.toString() })
        f.put(1, 0, "▁▂▃▄▅▆▇█")
        val image = f.draw()
        quadrants.forEachIndexed { column, (glyph, mask) ->
            val cell = f.area(0, column)
            repeat(4) { quadrant ->
                val x = if (quadrant % 2 == 0) 0.25f else 0.75f
                val y = if (quadrant < 2) 0.25f else 0.75f
                assertEquals("$glyph quadrant $quadrant", mask and (8 shr quadrant) != 0,
                    isInk(sample(image, cell, x, y)))
            }
            near("$glyph area", Integer.bitCount(mask) / 4.0, coverage(image, cell), 0.065)
        }
        repeat(8) { index ->
            near("eighth ${index + 1}", (index + 1) / 8.0,
                coverage(image, f.area(1, index)), 0.06)
        }
    }

    @Test
    fun shadesIncreaseDensityWithoutBecomingSolidBlocks() {
        val f = Fixture(faces(null).first().second, size = 43)
        f.put(0, 0, "░▒▓")
        val image = f.draw()
        val densities = (0..2).map { coverage(image, f.area(0, it)) }
        densities.forEachIndexed { index, density ->
            near("shade $index", (index + 1) / 4.0, density, 0.13)
        }
        assertTrue("Light < medium < dark", densities[0] < densities[1] && densities[1] < densities[2])
    }

    @Test
    fun singleDoubleAndMixedJunctionsHaveTheCorrectEdgeBranchesAndConnectedLines() {
        val f = Fixture(faces(null).first().second, size = 47)
        val junctions = listOf('┼' to (1 to 1), '╬' to (2 to 2),
            '╪' to (2 to 1), '╫' to (1 to 2))
        junctions.forEachIndexed { index, pair -> f.put(0, index * 2, pair.first.toString()) }
        f.put(1, 0, "───")
        f.put(2, 0, "═══")
        repeat(3) { row -> f.put(row, 10, "│"); f.put(row, 12, "║") }
        val image = f.draw()
        junctions.forEachIndexed { index, (glyph, branches) ->
            val cell = f.area(0, index * 2)
            assertEquals("$glyph left", branches.first, runs(vertical(image, cell.left + 1, cell)))
            assertEquals("$glyph right", branches.first, runs(vertical(image, cell.right - 2, cell)))
            assertEquals("$glyph top", branches.second, runs(horizontal(image, cell.top + 1, cell)))
            assertEquals("$glyph bottom", branches.second, runs(horizontal(image, cell.bottom - 2, cell)))
        }
        for (row in 1..2) {
            val middle = f.area(row, 1)
            val ys = (middle.top until middle.bottom).filter {
                isInk(image.getPixel(middle.centerX(), it))
            }
            assertTrue("Line has ink", ys.isNotEmpty())
            ys.forEach { y ->
                assertTrue("Horizontal seam in row $row at y=$y",
                    (0 until f.area(row, 0, 3).right).all { isInk(image.getPixel(it, y)) })
            }
        }
        for (column in listOf(10, 12)) {
            val middle = f.area(1, column)
            val xs = (middle.left until middle.right).filter {
                isInk(image.getPixel(it, middle.centerY()))
            }
            assertTrue("Vertical line has ink", xs.isNotEmpty())
            xs.forEach { x ->
                assertTrue("Vertical seam at x=$x", (f.area(0, column).top until
                    f.area(2, column).bottom).all { isInk(image.getPixel(x, it)) })
            }
        }
    }

    @Test
    fun powerlineSeparatorsKeepFilledOutlineAndDirectionSemantics() {
        val f = Fixture(faces(null).first().second, size = 47)
        f.put(0, 0, "")
        val image = f.draw()
        for (column in listOf(0, 2)) {
            val cell = f.area(0, column)
            val pointsRight = column == 0
            assertEquals("Triangle direction at column $column", pointsRight,
                isInk(sample(image, cell, 0.15f, 0.15f)))
            assertEquals("Triangle opposite side at column $column", !pointsRight,
                isInk(sample(image, cell, 0.85f, 0.15f)))
            near("Filled triangle area", 0.5, coverage(image, cell), 0.075)
            val outline = f.area(0, column + 1)
            assertEquals("Chevron crosses its middle vertical twice", 2,
                runs(vertical(image, outline.centerX(), outline)))
            assertTrue("Outline must not be filled",
                coverage(image, outline) < coverage(image, cell) * 0.65)
            assertFalse("Chevron interior stays empty", isInk(sample(image, outline,
                if (pointsRight) 0.15f else 0.85f, 0.5f)))
        }
    }

    @Test
    fun cjkUsesNaturalGlyphSizeAndExactlyTwoLogicalColumns() {
        for ((name, face) in faces(null)) {
            val f = Fixture(face, size = 43, line = 1.3f, letter = 0.1375f)
            f.put(0, 0, "  中A界B")
            assertEquals("$name logical columns", 8, f.emulator.cursorCol)
            val image = f.draw()
            for ((column, text) in listOf(2 to "中", 5 to "界")) {
                val native = nativeInk(face, f.size, text)
                assertTrue("Precondition: native CJK fits its two-column span",
                    native.width() < 2 * f.renderer.mFontWidth)
                val actual = inkBounds(image, f.area(0, column, 2))
                near("$name $text native width", native.width().toDouble(), actual.width().toDouble(), 1.0)
                near("$name $text native height", native.height().toDouble(), actual.height().toDouble(), 1.0)
            }
            val control = Fixture(face, size = f.size, line = 1.3f, letter = 0.1375f)
            control.put(0, 4, "A")
            control.put(0, 7, "B")
            val reference = control.draw()
            for (column in listOf(4, 7)) {
                assertSameArea("$name ASCII after CJK at $column", image, reference, f.area(0, column))
            }
        }
    }

    @Test
    fun longAsciiKeepsColumnsAcrossEquivalentStyleAndSelectionRunSplits() {
        for ((name, face) in faces(null)) {
            val text = "HMi.xW".repeat(10)
            val f = Fixture(face, size = 29, letter = 0.0375f, columns = text.length + 2)
            f.put(0, 0, text)
            assertEquals(text.length, f.emulator.cursorCol)
            val plain = f.draw()
            val styled = Fixture(face, size = 29, letter = 0.0375f, columns = f.columns)
            styled.put(0, 0, text.take(13) + "\u001b[38;2;236;132;220m" +
                text.substring(13, 37) + "\u001b[0m" + text.substring(37))
            assertSameArea("$name style split must not reposition a run", plain, styled.draw(),
                f.area(0, 0, f.columns))
            val selected = f.draw(Selection(0, 0, 13, 36))
            val inverse = Fixture(face, size = 29, letter = 0.0375f, columns = f.columns)
            inverse.emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = BG
            inverse.emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = FG
            inverse.put(0, 0, text)
            val inverseReference = inverse.draw()
            repeat(text.length) { column ->
                val cell = f.area(0, column)
                cell.inset(1, 1)
                // Compare identical ink/background colours: contrast-dependent text AA is
                // not a position change. The inverse control has no selection/run split.
                assertSameArea("$name selection changed glyph position at column $column",
                    if (column in 13..36) inverseReference else plain, selected, cell)
            }
        }
    }

    @Test
    fun widePuaBorrowsOnlyACompatibleRealRightSpaceAndPreservesItsAspectRatio() {
        val (face, icon) = wideIcon()
        val f = iconFixture(face)
        f.put(0, 0, icon + " ")
        assertEquals("The icon and space still occupy two logical columns", 2, f.emulator.cursorCol)
        val image = f.draw()
        val borrowed = inkBounds(image, f.area(0, 0, 2))
        val native = nativeInk(face, f.size, icon)
        assertTrue("The safe space must actually be used", inkBounds(image, f.area(0, 1)).width() > 0)
        assertAspect("borrowed icon", native, borrowed)

        val end = iconFixture(face)
        end.put(0, end.columns - 1, icon)
        val confined = inkBounds(end.draw(), end.area(0, end.columns - 1))
        assertAspect("confined icon", native, confined)
        assertTrue("Borrowing should retain a larger natural glyph than confinement",
            borrowed.height() >= confined.height() + 2)
    }

    @Test
    fun blankForegroundChangesAndEquivalentBackgroundsDoNotBlockSafeBorrowing() {
        val (face, icon) = wideIcon()
        val control = iconFixture(face)
        control.put(0, 0, icon + " ")
        val reference = control.draw()
        for (space in listOf("\u001b[38;2;20;200;40m \u001b[0m", "\u001b[48;2;10;20;30m \u001b[0m")) {
            val changed = iconFixture(face)
            changed.put(0, 0, icon + space)
            assertSameArea("An empty, undecorated cell with the same background remains borrowable",
                reference, changed.draw(), control.area(0, 0, 2))
        }
    }

    @Test
    fun puaCannotBorrowAcrossContentBackgroundDecorationCursorOrSelectionConflicts() {
        val (face, icon) = wideIcon()
        val end = iconFixture(face)
        end.put(0, end.columns - 1, icon)
        val confinedHeight = inkBounds(end.draw(), end.area(0, end.columns - 1)).height()
        val cases = listOf(
            Conflict("content", "A"),
            Conflict("background", "\u001b[48;2;140;40;20m \u001b[0m"),
            Conflict("underline", "\u001b[4m \u001b[0m"),
            Conflict("strikethrough", "\u001b[9m \u001b[0m"),
            Conflict("cursor on right", " ", cursorColumn = 1),
            Conflict("cursor on icon", " ", cursorColumn = 0, inverseIcon = true),
            Conflict("selection on right", " ", selection = Selection(0, 0, 1, 1)),
            Conflict("selection on icon", " ", selection = Selection(0, 0, 0, 0), inverseIcon = true),
        )
        for (case in cases) {
            val f = iconFixture(face)
            f.put(0, 0, icon + case.neighbor)
            case.cursorColumn?.let { f.put(0, it, "\u001b[?25h") }
            val image = f.draw(case.selection)
            val bounds = inkBounds(image, f.area(0, 0),
                if (case.inverseIcon) BG else FG, if (case.inverseIcon) FG else BG)
            near("${case.name} must confine the icon", confinedHeight.toDouble(), bounds.height().toDouble(), 1.0)
        }
    }

    @Test
    fun replacingABorrowedSpaceRetractsInkAndMatchesAFreshFrameWithoutGhosts() {
        val (face, icon) = wideIcon()
        val f = iconFixture(face)
        f.put(0, 0, icon + " ")
        val reused = f.draw()
        assertTrue("Precondition: visible borrowed ink", inkBounds(reused, f.area(0, 1)).width() > 0)
        f.put(0, 1, "A")
        f.draw(target = reused)
        val fresh = iconFixture(face)
        fresh.put(0, 0, icon + "A")
        assertSameArea("New content retracts borrowed ink on the next complete View-style frame",
            fresh.draw(), reused, f.area(0, 0, f.columns))
        f.put(0, 1, " ")
        f.draw(target = reused)
        assertTrue("Clearing the neighbor makes the real space borrowable again",
            inkBounds(reused, f.area(0, 1)).width() > 0)
    }

    @Test
    fun defaultAndCompressedBaselinesStayPutAndExtraLeadingIsSplitInHalf() {
        val face = faces(null).first().second
        val size = 37
        val paint = textPaint(face, size)
        val baselineHeight = ceil(paint.fontSpacing).toInt()
        for (line in listOf(0.7f, 1f, 1.3f)) {
            val f = Fixture(face, size, line)
            f.put(1, 2, "Hg")
            val actual = f.draw()
            val reference = bitmap(actual.width, actual.height)
            val canvas = Canvas(reference)
            canvas.drawColor(BG)
            val height = f.renderer.mFontLineSpacing
            // Old first-row baseline is H. Only the additional integral leading is halved.
            val extra = (height - baselineHeight).coerceAtLeast(0)
            val expectedBaseline = 2f * height + extra / 2f
            canvas.drawText("Hg", 2 * f.renderer.mFontWidth, expectedBaseline, paint)
            val area = f.area(1, 1, 5)
            val expected = inkBounds(reference, area)
            val observed = inkBounds(actual, area)
            near("line=$line baseline top", expected.top.toDouble(), observed.top.toDouble(), 1.0)
            near("line=$line baseline bottom", expected.bottom.toDouble(), observed.bottom.toDouble(), 1.0)
            near("line=$line keeps the natural text height", expected.height().toDouble(), observed.height().toDouble(), 1.0)
        }
    }

    @Test
    fun hiddenDimInverseAndTrueColorApplyToGraphicsWithoutLeakingItalicState() {
        val f = Fixture(faces(null).first().second, size = 43)
        f.put(0, 0, "█\u001b[2m█\u001b[0;8m█\u001b[0;7m█\u001b[0m")
        f.put(1, 0, "\u001b[3mH\u001b[0m█\u001b[38;2;33;177;211m█")
        f.put(2, 0, "\u001b[8mH\u001b[0m")
        val image = f.draw()
        assertSolid("normal block", image, f.area(0, 0), FG)
        assertSolid("hidden block", image, f.area(0, 2), BG)
        assertSolid("inverse block", image, f.area(0, 3), BG)
        val dim = sample(image, f.area(0, 1), 0.5f, 0.5f)
        assertTrue("Dim is visible but darker than the original foreground",
            Color.red(dim) in 1 until Color.red(FG) &&
                Color.green(dim) in 1 until Color.green(FG) &&
                Color.blue(dim) in 1 until Color.blue(FG))
        assertSolid("italic state cannot distort a following graphic", image, f.area(1, 1), FG)
        assertSolid("true-color graphic", image, f.area(1, 2), Color.rgb(33, 177, 211))
        assertSolid("hidden ordinary glyph", image, f.area(2, 0), BG)
    }

    @Test
    fun fractionalScrollDoesNotMakeBandsInCellBackgrounds() {
        val f = Fixture(faces(null).first().second, size = 31)
        val color = Color.rgb(40, 110, 180)
        repeat(f.rows) { f.put(it, 0, "\u001b[48;2;40;110;180m" + " ".repeat(f.columns)) }
        val image = bitmap(ceil(f.columns * f.renderer.mFontWidth).toInt(),
            f.renderer.mFontLineSpacingAndAscent + f.rows * f.renderer.mFontLineSpacing)
        val canvas = Canvas(image)
        canvas.drawColor(BG)
        val fraction = 0.375f
        f.renderer.render(f.emulator, canvas, 0, fraction, -1, -1, -1, -1)
        val boundary = (f.renderer.mFontLineSpacingAndAscent + (1 - fraction) * f.renderer.mFontLineSpacing).toInt()
        assertSolid("fractional background row boundary", image, Rect(1, boundary - 2, image.width - 1, boundary + 3), color)
    }

    @Test
    fun cachedGlyphMetricsDoNotLeakAcrossBoldItalicChanges() {
        val face = faces(null).first().second
        val warm = Fixture(face, size = 41, line = 1.3f, letter = -0.05f)
        for (style in listOf("", "\u001b[1m", "\u001b[3m", "\u001b[1;3m", "", "\u001b[3m")) {
            val payload = "\u001b[0m\u001b[2J\u001b[H" + style + "中 A" + "\u001b[0m"
            warm.feed(payload)
            val current = warm.draw()
            val fresh = Fixture(face, size = 41, line = 1.3f, letter = -0.05f)
            fresh.feed(payload)
            assertSameArea("warm metric cache must match a fresh renderer", fresh.draw(), current,
                Rect(0, 0, current.width, current.height))
        }
    }

    @Test
    fun combiningMarksAndExistingMonospaceLigaturesKeepNativeShaping() {
        val face = faces(null).first().second
        val f = Fixture(face, size = 37, columns = 40)
        val text = "é -> == != ffi"
        f.put(0, 2, text)
        assertEquals("The combining mark must not consume a column", 16, f.emulator.cursorCol)
        val actual = f.draw()
        val reference = bitmap(actual.width, actual.height)
        val canvas = Canvas(reference)
        canvas.drawColor(BG)
        canvas.clipRect(0, f.renderer.mFontLineSpacingAndAscent, reference.width, reference.height)
        val padded = ("  " + text + " ".repeat(f.columns - 16)).toCharArray()
        val paint = textPaint(face, f.size)
        canvas.drawTextRun(padded, 0, padded.size, 0, padded.size, 0f,
            ceil(paint.fontSpacing), false, paint)
        assertSameArea("Native mark/ligature shaping must survive grid placement", reference, actual,
            f.area(0, 0, f.columns))
    }

    private fun faces(fallback: String?): List<Pair<String, Typeface>> =
        listOf("jetbrains_mono", "noto_sans_sc").map {
            "$it/${fallback ?: "none"}" to fonts.resolveTypeface(it, fallback)
        }

    private fun iconFixture(face: Typeface) = Fixture(face, size = 53, letter = -0.15f, columns = 8)

    private fun wideIcon(): Pair<Typeface, String> {
        val candidates = listOf(0xf07b, 0xf120, 0xf121, 0xf085, 0xf17c, 0xf1b0,
            0xf0c2, 0xf013, 0xe620, 0xe73c, 0xf15b, 0xf0c7)
        for ((_, face) in faces(null)) {
            val f = iconFixture(face)
            val paint = textPaint(face, f.size)
            for (cp in candidates) {
                val icon = String(Character.toChars(cp))
                if (!paint.hasGlyph(icon)) continue
                val bounds = nativeInk(face, f.size, icon)
                // An ink-based precondition, not advance alone and not a renderer-helper decision.
                if (bounds.width() > f.renderer.mFontWidth + 5 && bounds.height() > 18) {
                    f.put(0, 0, icon)
                    assertEquals("The PUA fixture must occupy exactly one logical column", 1,
                        f.emulator.cursorCol)
                    return face to icon
                }
            }
        }
        throw AssertionError("Bundled font chains supplied no genuinely overwide one-column PUA fixture")
    }

    private inner class Fixture(
        face: Typeface,
        val size: Int = 37,
        line: Float = 1f,
        letter: Float = 0f,
        val columns: Int = 24,
        val rows: Int = 3,
    ) {
        val renderer = TerminalRenderer(size, face, line, letter)
        val emulator = TerminalEmulator(NoOutput, columns, rows,
            ceil(renderer.mFontWidth).toInt(), renderer.mFontLineSpacing, 100, NoClient)

        init {
            emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = FG
            emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = BG
            emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = FG
            feed("\u001b[?25l")
        }

        fun feed(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            emulator.append(bytes, bytes.size)
        }

        fun put(row: Int, column: Int, text: String) = feed("\u001b[${row + 1};${column + 1}H$text")

        fun draw(selection: Selection? = null, target: Bitmap? = null): Bitmap {
            val image = target ?: bitmap(ceil(columns * renderer.mFontWidth).toInt(),
                renderer.mFontLineSpacingAndAscent + rows * renderer.mFontLineSpacing)
            val canvas = Canvas(image)
            // TerminalView owns clearing the default background on EVERY frame. In particular,
            // rendering twice on a dirty bitmap without this clear is not a valid ghosting test.
            canvas.drawColor(emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
            renderer.render(emulator, canvas, 0, selection?.y1 ?: -1, selection?.y2 ?: -1,
                selection?.x1 ?: -1, selection?.x2 ?: -1)
            return image
        }

        fun area(row: Int, column: Int, span: Int = 1) = Rect(
            ceil(column * renderer.mFontWidth).toInt(),
            renderer.mFontLineSpacingAndAscent + row * renderer.mFontLineSpacing,
            floor((column + span) * renderer.mFontWidth).toInt(),
            renderer.mFontLineSpacingAndAscent + (row + 1) * renderer.mFontLineSpacing,
        )
    }

    private data class Selection(val y1: Int, val y2: Int, val x1: Int, val x2: Int)
    private data class Conflict(val name: String, val neighbor: String,
        val cursorColumn: Int? = null, val selection: Selection? = null, val inverseIcon: Boolean = false)

    private fun bitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { images += it }

    private fun textPaint(face: Typeface, size: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        textSize = size.toFloat()
        color = FG
    }

    private fun nativeInk(face: Typeface, size: Int, text: String): Rect {
        val image = bitmap(size * 8, size * 6)
        Canvas(image).apply {
            drawColor(BG)
            drawText(text, size * 2f, size * 3f, textPaint(face, size))
        }
        return inkBounds(image, Rect(0, 0, image.width, image.height))
    }

    private fun inkBounds(image: Bitmap, area: Rect, fore: Int = FG, back: Int = BG): Rect {
        val result = Rect()
        for (y in area.top until area.bottom) for (x in area.left until area.right) {
            if (isInk(image.getPixel(x, y), fore, back)) {
                result.union(x - area.left, y - area.top, x - area.left + 1, y - area.top + 1)
            }
        }
        return result
    }

    private fun isInk(color: Int, fore: Int = FG, back: Int = BG): Boolean =
        distance(color, fore) < distance(color, back)

    private fun distance(a: Int, b: Int): Int {
        val r = Color.red(a) - Color.red(b)
        val g = Color.green(a) - Color.green(b)
        val blue = Color.blue(a) - Color.blue(b)
        return r * r + g * g + blue * blue
    }

    private fun sample(image: Bitmap, area: Rect, x: Float, y: Float): Int = image.getPixel(
        area.left + (area.width() * x).toInt(), area.top + (area.height() * y).toInt())

    private fun coverage(image: Bitmap, area: Rect): Double {
        var sum = 0.0
        for (y in area.top until area.bottom) for (x in area.left until area.right) {
            // Mean channel coverage also measures stipple and antialiased diagonals, without
            // demanding a font-specific or implementation-specific shade pattern.
            sum += ((Color.red(image.getPixel(x, y)) - Color.red(BG)).toDouble() /
                (Color.red(FG) - Color.red(BG))).coerceIn(0.0, 1.0)
        }
        return sum / (area.width() * area.height())
    }

    private fun vertical(image: Bitmap, x: Int, area: Rect) =
        (area.top until area.bottom).map { isInk(image.getPixel(x, it)) }

    private fun horizontal(image: Bitmap, y: Int, area: Rect) =
        (area.left until area.right).map { isInk(image.getPixel(it, y)) }

    private fun runs(values: List<Boolean>): Int = values.indices.count { values[it] && (it == 0 || !values[it - 1]) }

    private fun assertSolid(label: String, image: Bitmap, area: Rect, expected: Int) {
        for (y in area.top until area.bottom) for (x in area.left until area.right) {
            if (image.getPixel(x, y) != expected) {
                throw AssertionError("$label pixel ($x,$y): expected ${Integer.toHexString(expected)}, " +
                    "actual ${Integer.toHexString(image.getPixel(x, y))}")
            }
        }
    }

    private fun assertSameArea(label: String, first: Bitmap, second: Bitmap, area: Rect) {
        for (y in area.top until area.bottom) for (x in area.left until area.right) {
            if (first.getPixel(x, y) != second.getPixel(x, y)) {
                throw AssertionError("$label differs at ($x,$y)")
            }
        }
    }

    private fun assertAspect(label: String, native: Rect, actual: Rect) {
        assertTrue("$label must have visible ink", actual.width() > 0 && actual.height() > 0)
        val expected = native.width().toDouble() / native.height()
        near("$label aspect ratio", expected, actual.width().toDouble() / actual.height(), expected * 0.13)
    }

    private fun near(label: String, expected: Double, actual: Double, tolerance: Double) =
        assertEquals(label, expected, actual, tolerance)

    private object NoOutput : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private object NoClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private companion object {
        val FG: Int = Color.rgb(236, 132, 220)
        val BG: Int = Color.rgb(10, 20, 30)
    }
}
