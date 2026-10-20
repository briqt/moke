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
            if (!name.startsWith("tmux_") && name !in setOf("session_disconnect", "close_connection_confirm", "close_tmux_connection_confirm")) continue
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
