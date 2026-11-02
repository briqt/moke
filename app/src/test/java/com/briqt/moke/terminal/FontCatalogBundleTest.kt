package com.briqt.moke.terminal

import com.briqt.moke.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FontCatalogBundleTest {
    @Test
    fun onlyTheDefaultPrimaryIsAnInstalledCatalogFont() {
        assertEquals(listOf("jetbrains_mono"), FontCatalog.all.filter { it.bundled }.map { it.id })
    }

    @Test
    fun defaultCjkFallbackUsesTheSystem() {
        assertEquals("", SettingsStore.DEFAULT_FALLBACK_FONT_ID)
    }

    @Test
    fun mapleRemainsAnOptionalPinnedDownload() {
        val maple = FontCatalog.byId("maple_mono")
        assertFalse(maple.bundled)
        assertNotNull(maple.url)
        assertEquals("1bd6b4be3062e6ef2b4aaa44e044d05efa4501afd3c42367842154bdb0367d0b", maple.sha256)
    }

    @Test
    fun unsetOrMissingSelectionsFallBackIndependently() {
        val missing: (String) -> Boolean = { false }
        for (id in listOf(null, "", "maple_mono", "noto_sans_sc", "missing_font")) {
            assertEquals("jetbrains_mono", FontCatalog.primaryId(id, missing))
            assertEquals("", FontCatalog.fallbackId(id, missing))
        }
        assertEquals("jetbrains_mono", FontCatalog.primaryId("jetbrains_mono", missing))
    }

    @Test
    fun existingDownloadedImportedAndLegacyFilesKeepTheirSelections() {
        val local = setOf("maple_mono", "noto_sans_sc", "user_fixture")
        for (id in local) {
            assertEquals(id, FontCatalog.primaryId(id, local::contains))
            assertEquals(id, FontCatalog.fallbackId(id, local::contains))
        }
        assertEquals("", FontCatalog.fallbackId("", local::contains))
    }

    @Test
    fun localOnlyLegacyFontsAreVisibleOnlyWhileTheirFileExists() {
        val absent = FontCatalog.visibleFonts { false }.map { it.id }
        assertFalse("noto_sans_sc" in absent)
        assertTrue("maple_mono" in absent)
        assertTrue("jetbrains_mono" in absent)
        assertTrue(FontCatalog.visibleFonts { it == "noto_sans_sc" }.any { it.id == "noto_sans_sc" })
    }

    @Test
    fun sourceResourcesContainOnlyThePrimaryAndSymbolFallbacks() {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val files = File(root, "app/src/main/res/font").listFiles()!!.filter { it.isFile }.map { it.name }.toSet()
        assertEquals(setOf("jetbrains_mono.ttf", "noto_sans_symbols2.ttf", "symbols_nerd_font.ttf"), files)
    }
}
