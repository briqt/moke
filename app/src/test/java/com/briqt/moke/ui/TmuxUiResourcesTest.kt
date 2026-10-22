package com.briqt.moke.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class TmuxUiResourcesTest {
    private val root = listOf(File("src/main/res"), File("app/src/main/res"))
        .first { File(it, "values/strings.xml").isFile }

    private fun resources(locale: String): Map<String, Map<String, String>> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }
        val elements = factory.newDocumentBuilder().parse(File(root, "$locale/strings.xml")).documentElement.childNodes
        val result = linkedMapOf<String, Map<String, String>>()
        for (i in 0 until elements.length) {
            val element = elements.item(i) as? Element ?: continue
            val name = element.getAttribute("name")
            if (!name.startsWith("tmux_") && !name.startsWith("session_title_") && name !in setOf(
                    "session_disconnect", "close_connection_confirm", "close_tmux_connection_confirm", "session_close_plain_message",
                    "action_cancel", "action_refresh", "group_current_directory", "group_connection", "group_project",
                    "terminal_keyboard_space_hidden",
                )) continue
            val values = if (element.tagName == "plurals") {
                val items = element.getElementsByTagName("item")
                (0 until items.length).associate { index ->
                    val item = items.item(index) as Element
                    item.getAttribute("quantity") to item.textContent
                }
            } else mapOf("value" to element.textContent)
            assertNull("Duplicate $name", result.put(name, values))
        }
        return result
    }

    @Test fun `tmux resources retain both locales and every full name placeholder`() {
        val english = resources("values")
        val chinese = resources("values-zh")
        assertEquals(english.keys, chinese.keys)
        val problems = setOf("tmux_current_unknown", "tmux_result_unknown", "tmux_target_changed", "tmux_session_changed",
            "tmux_remote_ended", "tmux_name_exists", "tmux_disconnected")
        for (locale in listOf(english, chinese)) {
            assertTrue(locale.keys.containsAll(problems))
            problems.forEach { assertTrue(locale.getValue(it).getValue("value").isNotBlank()) }
        }
        val placeholders = Regex("%[0-9]+\\$[sd]")
        for ((key, variants) in chinese) {
            for ((variant, text) in variants) {
                val reference = english.getValue(key)[variant] ?: english.getValue(key).getValue("other")
                assertEquals("$key/$variant", placeholders.findAll(reference).map { it.value }.toSet(),
                    placeholders.findAll(text).map { it.value }.toSet())
            }
        }
    }

    @Test fun `close choices and explicit creation use distinct short verbs in both locales`() {
        val english = resources("values")
        assertEquals("Disconnect", english.getValue("session_disconnect").getValue("value"))
        assertEquals("End session", english.getValue("tmux_kill").getValue("value"))
        assertEquals("Create and open", english.getValue("tmux_create_open").getValue("value"))
    }

    @Test fun `close body resources distinguish ordinary tmux and unverified outcomes without action instructions`() {
        val expected = mapOf(
            "values" to mapOf(
                "session_close_plain_message" to "Disconnecting may stop programs in this terminal.",
                "tmux_close_message" to "Disconnecting keeps the remote session. Ending the session stops its programs.",
                "tmux_close_unknown" to "The remote session cannot be verified. You can disconnect locally, but remote programs may stop.",
                "action_cancel" to "Cancel", "action_refresh" to "Refresh",
                "session_disconnect" to "Disconnect", "tmux_kill" to "End session",
            ),
            "values-zh" to mapOf(
                "session_close_plain_message" to "断开连接后，此终端中的程序可能停止。",
                "tmux_close_message" to "断开连接会保留远端会话。结束会话会终止其中的程序。",
                "tmux_close_unknown" to "暂时无法确认远端会话。可以断开本地连接，但无法保证远端程序继续运行。",
                "action_cancel" to "取消", "action_refresh" to "刷新",
                "session_disconnect" to "断开连接", "tmux_kill" to "结束会话",
            ),
        )
        for ((locale, values) in expected) {
            val texts = resources(locale)
            values.forEach { (key, value) -> assertEquals("$locale/$key", value, texts.getValue(key).getValue("value")) }
        }
    }

    @Test fun `title form and scoped grouping labels have both translations without changing old project semantics`() {
        val titleKeys = setOf("session_title_prefix", "session_title_automatic", "session_title_custom_automatic",
            "session_title_no_prefix", "session_title_use_automatic", "session_title_preview", "session_title_fields_hint")
        for (locale in listOf("values", "values-zh")) {
            val texts = resources(locale)
            titleKeys.forEach { assertTrue("$locale/$it", texts.getValue(it).getValue("value").isNotBlank()) }
        }
        for ((locale, expected) in mapOf(
            "values" to listOf("Project", "Project (current directory)", "Connection group", "Not enough space. Keyboard hidden."),
            "values-zh" to listOf("项目", "项目（当前目录）", "连接分组", "空间不足，已收起软键盘。"),
        )) {
            val texts = resources(locale)
            val actual = listOf("group_project", "group_current_directory", "group_connection", "terminal_keyboard_space_hidden")
                .map { texts.getValue(it).getValue("value") }
            assertEquals(locale, expected, actual)
        }
    }

    @Test fun `confirmed missing session is ended and ordinary connection close promises no process termination`() {
        for ((locale, ended) in listOf("values" to "Remote session ended", "values-zh" to "远端会话已结束")) {
            val texts = resources(locale)
            assertEquals(ended, texts.getValue("tmux_remote_ended").getValue("value"))
            val plain = texts.getValue("close_connection_confirm").getValue("value")
            assertTrue(plain.contains("%1\$s"))
            assertFalse(plain.contains("terminated", ignoreCase = true))
            assertFalse(plain.contains("终止"))
            assertTrue(texts.getValue("tmux_kill_confirm_message").getValue("value").contains("%1\$s"))
        }
    }
}
