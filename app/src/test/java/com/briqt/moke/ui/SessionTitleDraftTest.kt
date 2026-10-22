package com.briqt.moke.ui

import org.junit.Assert.*
import org.junit.Test

class SessionTitleDraftTest {
    @Test fun `automatic values are resolved live without becoming user values`() {
        val draft = SessionTitleDraft("", "")
        assertEquals("first · building", draft.preview("first", "building"))
        assertEquals("second · testing", draft.preview("second", "testing"))
        assertEquals(SessionTitleDraft("", ""), draft)
    }

    @Test fun `editing or restoring one field never fixes the other automatic field`() {
        val titleOnly = SessionTitleDraft("", "investigation")
        assertEquals("first · investigation", titleOnly.preview("first", "building"))
        assertEquals("second · investigation", titleOnly.preview("second", "testing"))
        val prefixOnly = SessionTitleDraft("manual", "")
        assertEquals("manual · building", prefixOnly.preview("first", "building"))
        assertEquals("manual · testing", prefixOnly.preview("second", "testing"))

        val both = SessionTitleDraft("manual", "investigation")
        assertEquals(titleOnly, both.copy(prefix = ""))
        assertEquals(prefixOnly, both.copy(title = ""))
        assertEquals("first · investigation", both.copy(prefix = "").preview("first", "building"))
        assertEquals("manual · building", both.copy(title = "").preview("first", "building"))
    }

    @Test fun `restoring both fields follows automatic updates again`() {
        val draft = SessionTitleDraft("manual", "investigation").copy(prefix = "", title = "")
        assertEquals("first · building", draft.preview("first", "building"))
        assertEquals("testing", draft.preview("", "testing"))
    }

    @Test fun `preview trims as save does but never rewrites draft text`() {
        val draft = SessionTitleDraft("  manual  ", "  investigation  ")
        assertEquals("manual · investigation", draft.preview("first", "building"))
        assertEquals("  manual  ", draft.prefix)
        assertEquals("  investigation  ", draft.title)
        assertEquals("first · building", SessionTitleDraft(" \t", "  ").preview(" first ", " building "))
    }

    @Test fun `no prefix or missing title never adds an empty separator`() {
        val automatic = SessionTitleDraft("", "")
        assertEquals("building", automatic.preview("", "building"))
        assertEquals("first", automatic.preview("first", ""))
        assertEquals("", automatic.preview("", ""))
        assertEquals("manual", SessionTitleDraft("manual", "").preview("", ""))
        assertEquals("investigation", SessionTitleDraft("", "investigation").preview("", ""))
    }
}
