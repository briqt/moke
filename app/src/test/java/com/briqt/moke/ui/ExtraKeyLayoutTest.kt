package com.briqt.moke.ui

import com.briqt.moke.terminal.KeyId
import com.briqt.moke.terminal.KeySeq
import com.briqt.moke.terminal.ModKind
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraKeyLayoutTest {

    @Test
    fun `空配置是默认两排 且行尾固定`() {
        val rows = ExtraKeyLayout.rows(null as String?)
        assertEquals(rows, ExtraKeyLayout.rows(""))
        assertEquals(rows, ExtraKeyLayout.rows("   "))
        assertEquals(7, rows[0].size)
        assertEquals(7, rows[1].size)
        assertEquals(ACTION_PANEL, (rows[0].last() as ExtraKey.Action).id)
        assertEquals(ACTION_COMPOSER, (rows[1].last() as ExtraKey.Action).id)
        assertEquals(
            listOf("ESC", "CTRL", "ALT", "↑", "HOME", "END"),
            rows[0].dropLast(1).map { it.label },
        )
        assertEquals(
            listOf("TAB", "⇧TAB", "←", "↓", "→", "^C"),
            rows[1].dropLast(1).map { it.label },
        )
        rows.flatten().filterIsInstance<ExtraKey.Key>().forEach { key ->
            assertNull(key.secondary)
            assertNull(key.icon)
        }
    }

    @Test
    fun `默认令牌来回写一致`() {
        val slots = ExtraKeyLayout.slots(ExtraKeyLayout.DEFAULT)
        assertEquals(ExtraKeyLayout.DEFAULT, ExtraKeyLayout.encode(slots))
        assertEquals(slots, ExtraKeyLayout.slots(ExtraKeyLayout.encode(slots)))
        assertEquals("", ExtraKeyLayout.encodeConfig(ExtraKeyConfig.DEFAULT))
        assertTrue(ExtraKeyLayout.parseConfig(ExtraKeyLayout.DEFAULT).isDefault)
    }

    @Test
    fun `非法配置整表退回默认`() {
        val fallback = ExtraKeyLayout.slots(null)
        listOf(
            "nope",
            "esc,ctrl|tab",
            "esc,no-such,alt,up,home,end|tab,btab,left,down,right,cc",
            "esc,ctrl,alt,up,home,end|tab,btab,left,down,right",
            "esc,ctrl,alt,up,home,end|tab,btab,left,down,right,cc|extra",
            " esc,ctrl,alt,up,home,end|tab,btab,left,down,right,cc",
            "ESC,ctrl,alt,up,home,end|tab,btab,left,down,right,cc",
        ).forEach { bad ->
            assertEquals(bad, fallback, ExtraKeyLayout.slots(bad))
            assertEquals(bad, ExtraKeyConfig.DEFAULT, ExtraKeyLayout.parseConfig(bad))
        }
    }

    @Test
    fun `换一格能存回去 行尾不动`() {
        val slots = ExtraKeyLayout.slots(null).map { it.toMutableList() }
        slots[0][0] = "shift"
        slots[1][5] = "f12"
        val stored = ExtraKeyLayout.encode(slots.map { it.toList() })
        val rows = ExtraKeyLayout.rows(stored)
        assertEquals(ModKind.Shift, (rows[0].first() as ExtraKey.Mod).kind)
        assertEquals("F12", rows[1][5].label)
        assertEquals(ACTION_PANEL, (rows[0].last() as ExtraKey.Action).id)
        assertEquals(ACTION_COMPOSER, (rows[1].last() as ExtraKey.Action).id)
        assertEquals(slots.map { it.toList() }, ExtraKeyLayout.slots(stored))
    }

    @Test
    fun `选择列表里的每个 id 都能变成键 且没有遗漏`() {
        val listed = ExtraKeyLayout.groups.flatMap { it.second }
        assertEquals(listed.distinct().size, listed.size)
        listed.forEach { id ->
            val key = ExtraKeyLayout.key(id)
            assertTrue(id, key.label.isNotEmpty())
            if (key is ExtraKey.Key) {
                assertTrue(id, key.key !is KeyId.Chars)
            }
        }
        assertEquals("\u0003", (ExtraKeyLayout.key("cc") as ExtraKey.Key).key.let { (it as KeyId.Macro).bytes })
        assertEquals("\u001b[Z", (ExtraKeyLayout.key("btab") as ExtraKey.Key).key.let { (it as KeyId.Macro).bytes })
        assertEquals("ALT⏎", ExtraKeyLayout.label("altenter"))
        assertEquals("M-⏎", ExtraKeyLayout.label("altenter", compactSymbols = true))
        assertEquals("\u001b\r", (ExtraKeyLayout.key("altenter") as ExtraKey.Key).key.let { (it as KeyId.Macro).bytes })
        assertEquals((1..12).map { "F$it" }, (1..12).map { ExtraKeyLayout.label("f$it") })
    }

    @Test
    fun `底部换掉的默认键补进面板 没换就不多出一组`() {
        assertEquals(emptyList<String>(), ExtraKeyLayout.displacedDefaults(null as String?))
        assertEquals(KEY_SECTIONS, panelSections(null))

        val slots = ExtraKeyLayout.slots(null).map { it.toMutableList() }
        slots[0][0] = "f1"   // 换掉 ESC
        slots[0][1] = "f2"   // 换掉 CTRL
        slots[1][5] = "f3"   // 换掉 ^C
        val stored = ExtraKeyLayout.encode(slots.map { it.toList() })
        assertEquals(listOf("esc", "ctrl", "cc"), ExtraKeyLayout.displacedDefaults(stored))
        val sections = panelSections(stored)
        assertEquals(KEY_SECTIONS.size + 1, sections.size)
        assertEquals(listOf("ESC", "CTRL", "^C"), sections.first().rows.flatten().map { it.label })
        assertEquals(KEY_SECTIONS[0], sections[1])
        assertEquals((4..12).map { "F$it" }, sections[2].rows.flatten().map { it.label })
        assertEquals(KEY_SECTIONS[2], sections[3])

        // 只是挪了位置（ESC 换到第二排）不算移走。
        val moved = ExtraKeyLayout.slots(null).map { it.toMutableList() }
        moved[0][0] = "tab"; moved[1][0] = "esc"
        assertEquals(emptyList<String>(), ExtraKeyLayout.displacedDefaults(ExtraKeyLayout.encode(moved)))
    }

    @Test
    fun `JSON v1 支持列数档位 高度档位 精简符号 副动作 组合键及标签图标来回编解码`() {
        for (cols in listOf(6, 7, 8)) {
            for (height in ExtraKeyHeight.entries) {
                for (compact in listOf(false, true)) {
                    val oneStep = ExtraKeySpec.CustomCombo(
                        steps = listOf(ComboStepSpec(ComboTarget.LEFT, ctrl = true)),
                        customLabel = "W←",
                    )
                    val twoStep = ExtraKeySpec.CustomCombo(
                        steps = listOf(ComboStepSpec.PREFIX_CTRL_B, ComboStepSpec(ComboTarget.PERCENT)),
                        customIcon = ExtraKeyIcon.SPLIT_VERTICAL,
                    )
                    val cfg = ExtraKeyConfig.DEFAULT
                        .resizeColumns(cols)
                        .copy(height = height, compactSymbols = compact)
                        .assignPrimary(0, 0, oneStep)
                        .assignSecondary(0, 0, twoStep)
                        .assignSecondary(1, 2, ExtraKeySpec.Builtin("home"))
                        .assignPrimary(1, 0, ExtraKeySpec.Builtin("tab", customIcon = ExtraKeyIcon.TAB))

                    val encoded = ExtraKeyLayout.encodeConfig(cfg)
                    assertTrue(encoded.startsWith("{"))
                    assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= ExtraKeyLayout.MAX_STORED_BYTES)
                    val decoded = ExtraKeyLayout.parseConfig(encoded)
                    assertEquals(cfg, decoded)

                    val rendered = ExtraKeyLayout.rows(encoded)
                    assertEquals(cols, rendered[0].size)
                    assertEquals(cols, rendered[1].size)
                    assertEquals(ACTION_PANEL, (rendered[0].last() as ExtraKey.Action).id)
                    assertEquals(ACTION_COMPOSER, (rendered[1].last() as ExtraKey.Action).id)

                    val firstSlot = rendered[0][0] as ExtraKey.Key
                    assertEquals("W←", firstSlot.label)
                    assertEquals("\u001b[1;5D", KeySeq.encode(firstSlot.key))
                    assertEquals(ExtraKeyIcon.SPLIT_VERTICAL, firstSlot.secondary?.icon)
                    assertEquals("\u0002%", KeySeq.encode(firstSlot.secondary!!.key))

                    val leftSlot = rendered[1][2] as ExtraKey.Key
                    assertEquals("↖", leftSlot.secondary?.microHint)
                }
            }
        }
    }

    @Test
    fun `列数在 6 7 8 之间切换保留倒T方向键且补齐与兜底面板一致`() {
        val six = ExtraKeyConfig.DEFAULT.resizeColumns(6)
        assertEquals(6, six.columns)
        assertEquals(5, six.editableColumns)
        val sixRows = ExtraKeyLayout.rows(six)
        assertEquals("↑", sixRows[0][3].label)
        assertEquals(listOf("←", "↓", "→"), sixRows[1].subList(2, 5).map { it.label })
        assertEquals(listOf("end", "cc"), ExtraKeyLayout.displacedDefaults(six))
        assertEquals(ExtraKeyConfig.DEFAULT, six.resizeColumns(7))

        val eight = ExtraKeyConfig.DEFAULT.resizeColumns(8)
        assertEquals(8, eight.columns)
        assertEquals(7, eight.editableColumns)
        val eightRows = ExtraKeyLayout.rows(eight)
        assertEquals("↑", eightRows[0][3].label)
        assertEquals(listOf("←", "↓", "→"), eightRows[1].subList(2, 5).map { it.label })
        assertEquals("PgUp", eightRows[0][6].label)
        assertEquals("PgDn", eightRows[1][6].label)
        assertEquals(emptyList<String>(), ExtraKeyLayout.displacedDefaults(eight))
        assertEquals(ExtraKeyConfig.DEFAULT, eight.resizeColumns(7))
        assertEquals(ExtraKeyConfig.DEFAULT, eight.resizeColumns(6).resizeColumns(7))
        assertTrue(six.resizeColumns(7).isDefault)
        assertEquals("", ExtraKeyLayout.encodeConfig(six.resizeColumns(7)))
    }

    @Test
    fun `列数缩减与扩回无损保留自定义组合键 标签 图标与副动作 并处理修饰键冲突`() {
        val col5Combo = ExtraKeySpec.CustomCombo(
            steps = listOf(ComboStepSpec.PREFIX_CTRL_B, ComboStepSpec(ComboTarget.LBRACE)),
            customLabel = "左换",
        )
        val col5Sec = ExtraKeySpec.CustomCombo(
            steps = listOf(ComboStepSpec.PREFIX_CTRL_B, ComboStepSpec(ComboTarget.RBRACE)),
        )
        val col6Combo = ExtraKeySpec.CustomCombo(
            steps = listOf(ComboStepSpec.PREFIX_CTRL_B, ComboStepSpec(ComboTarget.BANG)),
            customIcon = ExtraKeyIcon.WINDOW,
        )
        val custom7 = ExtraKeyConfig.DEFAULT
            .assignPrimary(0, 5, col5Combo)
            .assignSecondary(0, 5, col5Sec)
            .assignPrimary(1, 5, ExtraKeySpec.Builtin("f12", customLabel = "构建"))

        // 7 -> 6 -> JSON 来回 -> 7 无损恢复，恢复后 stash 为空不在 JSON 中残留
        val shrunkFrom7 = ExtraKeyLayout.parseConfig(ExtraKeyLayout.encodeConfig(custom7.resizeColumns(6)))
        assertEquals(6, shrunkFrom7.columns)
        val restored7 = shrunkFrom7.resizeColumns(7)
        assertEquals(custom7, restored7)
        assertFalse(ExtraKeyLayout.encodeConfig(restored7).contains("\"stash\""))

        // 8 -> 6 -> 8 及 8 -> 7 -> 6 -> 8 全程无损恢复第 6、7 可编辑列
        val custom8 = custom7
            .resizeColumns(8)
            .assignPrimary(0, 6, col6Combo)
            .assignSecondary(0, 6, ExtraKeySpec.Builtin("pgdn"))
            .assignPrimary(1, 6, ExtraKeySpec.Builtin("f11", customIcon = ExtraKeyIcon.SEARCH))
        val directBack8 = ExtraKeyLayout.parseConfig(ExtraKeyLayout.encodeConfig(custom8.resizeColumns(6))).resizeColumns(8)
        assertEquals(custom8, directBack8)

        val steppedBack8 = ExtraKeyLayout.parseConfig(
            ExtraKeyLayout.encodeConfig(
                ExtraKeyLayout.parseConfig(ExtraKeyLayout.encodeConfig(custom8.resizeColumns(7))).resizeColumns(6),
            ),
        ).resizeColumns(8)
        assertEquals(custom8, steppedBack8)

        // 暂存槽位含 shift，6 列期间用户又把 shift 放到 (0,0)，扩回 7 列时自动消解重复修饰且保留副动作
        val withShiftAtTail = ExtraKeyConfig.DEFAULT
            .assignPrimary(0, 5, ExtraKeySpec.Builtin("shift"))
            .assignSecondary(0, 5, ExtraKeySpec.Builtin("pgup"))
        val sixThenShift = ExtraKeyLayout.parseConfig(
            ExtraKeyLayout.encodeConfig(
                withShiftAtTail.resizeColumns(6).assignPrimary(0, 0, ExtraKeySpec.Builtin("shift")),
            ),
        )
        val expanded7 = sixThenShift.resizeColumns(7)
        assertEquals(ExtraKeySpec.Builtin("shift"), expanded7.rows[0][0].primary)
        assertFalse(expanded7.rows[0][5].primary.isModifier)
        assertEquals(ExtraKeySpec.Builtin("pgup"), expanded7.rows[0][5].secondary)
        val activeMods = ExtraKeyLayout.rows(expanded7).flatten().filterIsInstance<ExtraKey.Mod>().map { it.kind }
        assertEquals(activeMods.distinct().size, activeMods.size)
    }

    @Test
    fun `修饰键主槽位全局唯一自动互换且禁止设为副动作`() {
        val base = ExtraKeyConfig.DEFAULT
            .assignSecondary(0, 0, ExtraKeySpec.Builtin("pgup"))
            .assignSecondary(0, 1, ExtraKeySpec.Builtin("pgdn"))

        // 默认 (0,0)=esc, (0,1)=ctrl；把 (0,0) 设为 ctrl 时，(0,1) 自动换为 esc，且两侧副动作原样保留。
        val swapped = base.assignPrimary(0, 0, ExtraKeySpec.Builtin("ctrl"))
        assertEquals(ExtraKeySpec.Builtin("ctrl"), swapped.rows[0][0].primary)
        assertEquals(ExtraKeySpec.Builtin("esc"), swapped.rows[0][1].primary)
        assertEquals(ExtraKeySpec.Builtin("pgup"), swapped.rows[0][0].secondary)
        assertEquals(ExtraKeySpec.Builtin("pgdn"), swapped.rows[0][1].secondary)

        val mods = ExtraKeyLayout.rows(swapped).flatten().filterIsInstance<ExtraKey.Mod>().map { it.kind }
        assertEquals(listOf(ModKind.Ctrl, ModKind.Alt), mods)

        // 副动作拒绝写入修饰键。
        for (modId in listOf("ctrl", "alt", "shift")) {
            val unchanged = swapped.assignSecondary(0, 0, ExtraKeySpec.Builtin(modId))
            assertEquals(swapped, unchanged)
        }
    }

    @Test
    fun `组合键默认短标签紧凑不超过4字符且自定义标签过滤控制字符`() {
        assertEquals(
            "^Bd",
            ExtraKeySpec.CustomCombo(listOf(ComboStepSpec.PREFIX_CTRL_B, ComboStepSpec(ComboTarget.KEY_D)))
                .defaultLabel(false),
        )
        assertEquals(
            "^X^S",
            ExtraKeySpec.CustomCombo(listOf(ComboStepSpec.PREFIX_CTRL_X, ComboStepSpec(ComboTarget.KEY_S, ctrl = true)))
                .defaultLabel(false),
        )
        assertEquals(
            "M-b",
            ExtraKeySpec.CustomCombo(listOf(ComboStepSpec(ComboTarget.KEY_B, alt = true)))
                .defaultLabel(false),
        )
        assertEquals(
            "^←",
            ExtraKeySpec.CustomCombo(listOf(ComboStepSpec(ComboTarget.LEFT, ctrl = true)))
                .defaultLabel(false),
        )
        assertEquals(
            "⎋x",
            ExtraKeySpec.CustomCombo(listOf(ComboStepSpec.PREFIX_ESC, ComboStepSpec(ComboTarget.KEY_X)))
                .defaultLabel(false),
        )

        // 仅对在 KeySeq.encode 中实际生效的修饰生成前缀符号，不产生误导标签
        assertEquals("⎋", ComboStepSpec(ComboTarget.ESC, ctrl = true, alt = true, shift = true).autoLabel())
        assertEquals("⏎", ComboStepSpec(ComboTarget.ENTER, ctrl = true, shift = true).autoLabel())
        assertEquals("M-⏎", ComboStepSpec(ComboTarget.ENTER, alt = true, shift = true).autoLabel())
        assertEquals("⇧⇥", ComboStepSpec(ComboTarget.TAB, alt = true, shift = true).autoLabel())
        assertEquals("M-⇥", ComboStepSpec(ComboTarget.TAB, alt = true).autoLabel())
        assertEquals("^⌫", ComboStepSpec(ComboTarget.BACKSPACE, ctrl = true, shift = true).autoLabel())
        assertEquals("^M⌫", ComboStepSpec(ComboTarget.BACKSPACE, ctrl = true, alt = true).autoLabel())
        assertEquals(".", ComboStepSpec(ComboTarget.DOT, ctrl = true, shift = true).autoLabel())
        assertEquals("M-.", ComboStepSpec(ComboTarget.DOT, ctrl = true, alt = true).autoLabel())
        assertEquals("^-", ComboStepSpec(ComboTarget.MINUS, ctrl = true).autoLabel())
        assertEquals("^6", ComboStepSpec(ComboTarget.DIGIT_6, ctrl = true).autoLabel())
        assertEquals("1", ComboStepSpec(ComboTarget.DIGIT_1, ctrl = true).autoLabel())
        assertEquals("^A", ComboStepSpec(ComboTarget.KEY_A, ctrl = true, shift = true).autoLabel())

        // 常用符号补齐窗格交换/会话切换/拆窗等高频 ASCII 符号
        val commonChars = ComboTarget.forCategory(ComboTargetCategory.COMMON)
            .map { (it.keyId as KeyId.Chars).text }
            .toSet()
        for (sym in listOf("{", "}", "(", ")", "!", "^", "_", "@", "#", "+", "*")) {
            assertTrue("Missing symbol $sym in COMMON", sym in commonChars)
        }

        for (target in ComboTarget.entries) {
            val single = ExtraKeySpec.CustomCombo(listOf(ComboStepSpec(target, ctrl = true, alt = true)))
            val label = single.defaultLabel(false)
            assertTrue("$target -> $label", label.isNotEmpty() && label.codePointCount(0, label.length) <= 4)
            assertTrue("$target encode empty", KeySeq.encode(single.toKeyId()).isNotEmpty())
        }

        assertEquals("AB12", ExtraKeyLayout.sanitizeCustomLabel("  \u001b\nAB\u000012345  "))
        assertEquals("", ExtraKeyLayout.sanitizeCustomLabel(" \r\n\t\u0007 "))

        // CJK 视觉宽度计算与右上角微缩提示截断
        assertEquals(3, ExtraKeyLayout.visualUnits("F10"))
        assertEquals(4, ExtraKeyLayout.visualUnits("分屏"))
        assertEquals(8, ExtraKeyLayout.visualUnits("全选窗口"))
        assertEquals("F10", ExtraKeySpec.Builtin("f10").microHintLabel())
        assertEquals("分", ExtraKeySpec.Builtin("f10", customLabel = "分屏").microHintLabel())
        assertEquals("A分", ExtraKeySpec.Builtin("f10", customLabel = "A分B").microHintLabel())
    }

    @Test
    fun `损坏JSON 越界列数 非法副动作修饰键与超64KiB输入安全回退默认`() {
        val validJson = ExtraKeyLayout.encodeConfig(
            ExtraKeyConfig.DEFAULT.copy(height = ExtraKeyHeight.COMFORTABLE),
        )
        val corruptInputs = listOf(
            "{",
            "{not json}",
            """{"v":2,"cols":7,"height":"STANDARD","compact":false,"rows":[[],[]]}""",
            validJson.replace("\"cols\":7", "\"cols\":5"),
            validJson.replace("\"cols\":7", "\"cols\":9"),
            validJson.replace("\"COMFORTABLE\"", "\"HUGE\""),
            // 副动作塞入修饰键 ctrl
            validJson.replaceFirst("""{"p":{"t":"id","id":"esc"}}""", """{"p":{"t":"id","id":"esc"},"s":{"t":"id","id":"ctrl"}}"""),
            // 主动作出现重复修饰键 ctrl
            validJson.replaceFirst("""{"p":{"t":"id","id":"esc"}}""", """{"p":{"t":"id","id":"ctrl"}}"""),
            // 空步骤或三步组合键
            validJson.replaceFirst("""{"t":"id","id":"esc"}""", """{"t":"combo","steps":[]}"""),
            validJson.replaceFirst(
                """{"t":"id","id":"esc"}""",
                """{"t":"combo","steps":[{"k":"b","c":true},{"k":"x","c":true},{"k":"s"}]}""",
            ),
            // 未知内置 id 或未知图标
            validJson.replaceFirst(""""id":"esc"""", """"id":"nonexistent""""),
            validJson.replaceFirst("""{"t":"id","id":"esc"}""", """{"t":"id","id":"esc","icon":"bad_icon"}"""),
            // 损坏或越界的 stash 字段
            validJson.dropLast(1) + ""","stash":"bad"}""",
            validJson.dropLast(1) + ""","stash":[[]]}""",
            validJson.dropLast(1) + ""","stash":[[{"p":{"t":"id","id":"f1"}},{"p":{"t":"id","id":"f2"}}],[]]}""",
            validJson.dropLast(1) + ""","stash":[[{"p":{"t":"id","id":"f1"},"s":{"t":"id","id":"ctrl"}}],[]]}""",
            ExtraKeyLayout.encodeConfig(ExtraKeyConfig.DEFAULT.resizeColumns(8)).dropLast(1) +
                ""","stash":[[{"p":{"t":"id","id":"f1"}}],[]]}""",
            // 旧版紧凑串含重复修饰键或超 64 KiB
            "ctrl,ctrl,alt,up,home,end|tab,btab,left,down,right,cc",
            "esc," + "a".repeat(ExtraKeyLayout.MAX_STORED_BYTES + 10),
            // 超过 64 KiB 上限
            "{" + " ".repeat(ExtraKeyLayout.MAX_STORED_BYTES + 10) + "}",
        )
        for (bad in corruptInputs) {
            assertEquals(ExtraKeyConfig.DEFAULT, ExtraKeyLayout.parseConfig(bad))
            assertEquals(ExtraKeyLayout.slots(null), ExtraKeyLayout.slots(bad))
        }
    }

    @Test
    fun `全部内置标签 精简符号 组合键字形与字节回显符号在随包JetBrainsMono中均有非空glyf轮廓`() {
        val fontFile = listOf(
            File("src/main/res/font/jetbrains_mono.ttf"),
            File("app/src/main/res/font/jetbrains_mono.ttf"),
        ).firstOrNull { it.isFile } ?: error("Cannot locate jetbrains_mono.ttf")

        val fontInfo = inspectTrueTypeFont(fontFile.readBytes())
        val allIds = ExtraKeyLayout.groups.flatMap { it.second }
        val compactConfig = ExtraKeyConfig.DEFAULT.copy(compactSymbols = true)
        val describeSample = KeySeq.describeBytes("\u0000\u0001\u001b[1;5D\u001bOA\u001b[\u001c\u001d\u001e\u001f\r\n\t\u007f ")
        val allLabels = buildSet {
            allIds.forEach { id ->
                add(ExtraKeyLayout.label(id, compactSymbols = false))
                add(ExtraKeyLayout.label(id, compactSymbols = true))
                add(ExtraKeyLayout.microLabel(id))
            }
            KEY_SECTIONS.flatMap { it.rows.flatten() }.forEach { add(it.label) }
            panelSections(null).flatMap { it.rows.flatten() }.forEach { add(it.label) }
            panelSections(ExtraKeyLayout.encodeConfig(compactConfig)).flatMap { it.rows.flatten() }.forEach { add(it.label) }
            SINGLE_GLYPH_CHARS.forEach { add(it.toString()) }
            ComboTarget.entries.forEach { target ->
                add(target.display)
                add(target.shortGlyph)
                add(ComboStepSpec(target, ctrl = true, alt = true, shift = true).autoLabel(compactForTwoStep = false))
                add(ComboStepSpec(target, ctrl = true, alt = true, shift = true).autoLabel(compactForTwoStep = true))
            }
            add(describeSample)
        }

        for (label in allLabels) {
            var idx = 0
            while (idx < label.length) {
                val cp = label.codePointAt(idx)
                idx += Character.charCount(cp)
                if (cp == 0x20) continue
                val glyphId = fontInfo.cmap[cp] ?: 0
                assertTrue(
                    "Codepoint U+${cp.toString(16).uppercase()} in '$label' missing from jetbrains_mono.ttf",
                    glyphId > 0,
                )
                val glyfBytes = fontInfo.glyfLength(glyphId)
                assertTrue(
                    "Codepoint U+${cp.toString(16).uppercase()} (gid=$glyphId) in '$label' has empty glyf contour",
                    glyfBytes > 0,
                )
            }
        }
    }

    private class TrueTypeFontInfo(
        val cmap: Map<Int, Int>,
        private val glyphOffsets: IntArray,
    ) {
        fun glyfLength(glyphId: Int): Int {
            if (glyphId < 0 || glyphId + 1 >= glyphOffsets.size) return 0
            return glyphOffsets[glyphId + 1] - glyphOffsets[glyphId]
        }
    }

    /** 解析 TTF `cmap` + `head`/`maxp`/`loca`，同时校验 glyphId != 0 且 `glyf` 轮廓字节非空。 */
    private fun inspectTrueTypeFont(bytes: ByteArray): TrueTypeFontInfo {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buf.position(4)
        val numTables = buf.short.toInt() and 0xFFFF
        buf.position(12)
        var cmapOffset = -1
        var headOffset = -1
        var maxpOffset = -1
        var locaOffset = -1
        repeat(numTables) {
            val tagBytes = ByteArray(4).also { buf.get(it) }
            val tag = String(tagBytes, Charsets.US_ASCII)
            buf.int // checksum
            val offset = buf.int
            buf.int // length
            when (tag) {
                "cmap" -> cmapOffset = offset
                "head" -> headOffset = offset
                "maxp" -> maxpOffset = offset
                "loca" -> locaOffset = offset
            }
        }
        require(cmapOffset > 0 && headOffset > 0 && maxpOffset > 0 && locaOffset > 0) {
            "Missing required TrueType tables"
        }
        val indexToLocFormat = buf.getShort(headOffset + 50).toInt()
        val numGlyphs = buf.getShort(maxpOffset + 4).toInt() and 0xFFFF
        val glyphOffsets = IntArray(numGlyphs + 1) { gid ->
            if (indexToLocFormat == 0) {
                (buf.getShort(locaOffset + gid * 2).toInt() and 0xFFFF) * 2
            } else {
                buf.getInt(locaOffset + gid * 4)
            }
        }

        buf.position(cmapOffset + 2)
        val numSubtables = buf.short.toInt() and 0xFFFF
        var format12Offset = -1
        var format4Offset = -1
        repeat(numSubtables) {
            val platformId = buf.short.toInt() and 0xFFFF
            val encodingId = buf.short.toInt() and 0xFFFF
            val subOffset = cmapOffset + buf.int
            val saved = buf.position()
            buf.position(subOffset)
            val fmt = buf.short.toInt() and 0xFFFF
            if ((platformId == 3 || platformId == 0) && fmt == 12) format12Offset = subOffset
            if ((platformId == 3 && encodingId == 1 || platformId == 0) && fmt == 4) format4Offset = subOffset
            buf.position(saved)
        }
        val map = HashMap<Int, Int>()
        if (format12Offset > 0) {
            buf.position(format12Offset + 12)
            val numGroups = buf.int
            repeat(numGroups) {
                val startChar = buf.int
                val endChar = buf.int
                val startGlyph = buf.int
                for (cp in startChar..endChar) {
                    map[cp] = startGlyph + (cp - startChar)
                }
            }
            return TrueTypeFontInfo(map, glyphOffsets)
        }
        require(format4Offset > 0) { "No supported Unicode cmap subtable" }
        buf.position(format4Offset + 6)
        val segCount = (buf.short.toInt() and 0xFFFF) / 2
        buf.position(format4Offset + 14)
        val endCodes = IntArray(segCount) { buf.short.toInt() and 0xFFFF }
        buf.short // reservedPad
        val startCodes = IntArray(segCount) { buf.short.toInt() and 0xFFFF }
        val idDeltas = IntArray(segCount) { buf.short.toInt() }
        val idRangeOffsetPos = buf.position()
        val idRangeOffsets = IntArray(segCount) { buf.short.toInt() and 0xFFFF }
        for (i in 0 until segCount) {
            if (startCodes[i] == 0xFFFF) continue
            for (cp in startCodes[i]..endCodes[i]) {
                val gid = if (idRangeOffsets[i] == 0) {
                    (cp + idDeltas[i]) and 0xFFFF
                } else {
                    val glyphIndexPos = idRangeOffsetPos + i * 2 + idRangeOffsets[i] + (cp - startCodes[i]) * 2
                    val raw = buf.getShort(glyphIndexPos).toInt() and 0xFFFF
                    if (raw == 0) 0 else (raw + idDeltas[i]) and 0xFFFF
                }
                if (gid != 0) map[cp] = gid
            }
        }
        return TrueTypeFontInfo(map, glyphOffsets)
    }
}
