package com.briqt.moke.ui

import com.briqt.moke.data.GroupBy
import com.briqt.moke.data.Host
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SessionGroupingTest {
    private val host = Host(id = "one", label = "Work", host = "host.example", username = "user", group = "Manual")

    @Test fun `missing directory has only the host label and not the manual group`() {
        val result = sessionGroupLabel(GroupBy.PROJECT, host, "target")
        assertEquals("Work", result.label)
        assertNotEquals(sessionGroupLabel(GroupBy.CONNECTION, host, "target").key, result.key)
    }

    @Test fun `project uses the complete directory and target scope`() {
        val first = sessionGroupLabel(GroupBy.PROJECT, host, "target", "/one/project")
        assertEquals("Work /one/project", first.label)
        assertNotEquals(first.key, sessionGroupLabel(GroupBy.PROJECT, host, "target", "/two/project").key)
        assertNotEquals(first.key, sessionGroupLabel(GroupBy.PROJECT, host, "other-account", "/one/project").key)
        assertEquals(first.key, sessionGroupLabel(GroupBy.PROJECT, host.copy(label = "Renamed"), "target", "/one/project").key)
    }

    @Test fun `reported context does not borrow the outer host label or target`() {
        val report = sessionGroupLabel(GroupBy.PROJECT, host, "outer", "/work", "report-inner", "inner.example")
        assertEquals("inner.example /work", report.label)
        assertNotEquals(report.key, sessionGroupLabel(GroupBy.PROJECT, host, "outer", "/work").key)
    }

    @Test fun `directory keys cannot collide through punctuation or separators`() {
        assertNotEquals(
            sessionGroupLabel(GroupBy.PROJECT, host, "one|two", "/path").key,
            sessionGroupLabel(GroupBy.PROJECT, host, "one", "/two|path").key,
        )
    }

    @Test fun `saved project selection now means directory and manual grouping remains available`() {
        assertEquals(GroupBy.PROJECT, GroupBy.fromName("PROJECT", GroupBy.NONE))
        assertEquals("Manual", sessionGroupLabel(GroupBy.CONNECTION, host, "target").label)
        assertEquals(GroupBy.HOST, GroupBy.fromName("HOST", GroupBy.PROJECT))
        assertEquals(GroupBy.NONE, GroupBy.fromName("NONE", GroupBy.PROJECT))
    }

    @Test fun `drag snapshot excludes deleted IDs and preserves newly added items`() {
        assertEquals(listOf("c", "new", "a", "other"),
            mergeSessionOrder(listOf("a", "new", "c", "other"), listOf("c", "deleted", "a", "c")))
    }

    @Test fun `empty or unrelated drag snapshot leaves live order unchanged`() {
        assertEquals(listOf("a", "b"), mergeSessionOrder(listOf("a", "b"), listOf("deleted")))
        assertEquals(listOf("a", "b"), mergeSessionOrder(listOf("a", "b"), emptyList()))
    }
}
