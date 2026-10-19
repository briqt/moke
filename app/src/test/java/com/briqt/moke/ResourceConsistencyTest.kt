package com.briqt.moke

import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import org.xml.sax.InputSource

class ResourceConsistencyTest {
    private data class Resource(val type: String, val values: Map<String, String>)
    private val placeholders = Regex("%(?:(\\d+)\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?([tT])?([a-zA-Z%])")

    private fun signature(text: String): Map<Int, String> {
        var next = 1
        var previous = 0
        val result = linkedMapOf<Int, String>()
        for (match in placeholders.findAll(text)) {
            val conversion = match.groupValues[3]
            if (conversion == "%" || conversion == "n") continue
            val index = when {
                match.groupValues[1].isNotEmpty() -> match.groupValues[1].toInt()
                '<' in match.value -> previous
                else -> next++
            }
            previous = index
            val kind = if (match.groupValues[2].isNotEmpty()) "date" else conversion.lowercase()
            require(result[index] == null || result[index] == kind) { "Inconsistent placeholder type" }
            result[index] = kind
        }
        return result
    }

    private fun parse(xml: String): Map<String, Resource> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }
        val root = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        val result = linkedMapOf<String, Resource>()
        for (i in 0 until root.childNodes.length) {
            val element = root.childNodes.item(i) as? Element ?: continue
            if (element.tagName !in setOf("string", "plurals", "string-array")) continue
            if (element.getAttribute("translatable") == "false") continue
            val key = "${element.tagName}/${element.getAttribute("name")}"
            require(key !in result) { "Duplicate resource: $key" }
            val values = linkedMapOf<String, String>()
            if (element.tagName == "string") {
                values["value"] = element.textContent
            } else {
                val items = element.getElementsByTagName("item")
                for (j in 0 until items.length) {
                    val item = items.item(j) as Element
                    val quantity = if (element.tagName == "plurals") item.getAttribute("quantity") else j.toString()
                    require(quantity !in values) { "Duplicate item: $key/$quantity" }
                    values[quantity] = item.textContent
                }
                if (element.tagName == "plurals") require("other" in values) { "Missing plural fallback: $key" }
            }
            result[key] = Resource(element.tagName, values)
        }
        return result
    }

    @Test fun `Chinese and default resources have matching keys and parameter signatures`() {
        val root = listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { File(it, "values/strings.xml").isFile }
            ?: error("Cannot locate application resources")
        val english = parse(File(root, "values/strings.xml").readText())
        val chinese = parse(File(root, "values-zh/strings.xml").readText())
        assertEquals("Resource keys differ", english.keys, chinese.keys)
        for ((key, translated) in chinese) {
            val original = english.getValue(key)
            for ((variant, text) in translated.values) {
                val reference = original.values[variant] ?: if (original.type == "plurals") original.values.getValue("other") else error("Missing $key/$variant")
                assertEquals("Placeholder mismatch: $key/$variant", signature(reference), signature(text))
            }
        }
    }

    @Test fun `the guard rejects duplicate resources and duplicate plural branches`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("<resources><string name=\"a\">one</string><string name=\"a\">two</string></resources>")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("<resources><plurals name=\"a\"><item quantity=\"other\">one</item><item quantity=\"other\">two</item></plurals></resources>")
        }
    }

    @Test fun `the guard detects changed parameter types and missing positional arguments`() {
        assertNotEquals(signature("%1\$s"), signature("%1\$d"))
        assertNotEquals(signature("%1\$s %2\$d"), signature("%1\$s"))
        assertEquals(signature("%1\$s %2\$d"), signature("%2\$d %1\$s"))
        assertTrue(signature("100%%").isEmpty())
    }
}
