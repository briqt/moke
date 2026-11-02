package com.briqt.moke.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Paint
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.briqt.moke.terminal.FontCatalog
import com.briqt.moke.terminal.FontRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated DataStore and font directory: never mutate the target app's saved settings or fonts. */
@RunWith(AndroidJUnit4::class)
class FontSelectionMigrationTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var store: DataStore<Preferences>
    private lateinit var settings: SettingsStore
    private val primaryKey = stringPreferencesKey("primary_font_id")
    private val fallbackKey = stringPreferencesKey("fallback_font_id")

    @Before fun createIsolatedStore() {
        root = File(target.cacheDir, "font-selection-${UUID.randomUUID()}").apply { mkdirs() }
        val files = File(root, "files").apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getFilesDir(): File = files
        }
        store = PreferenceDataStoreFactory.create(scope = scope) { File(root, "settings.preferences_pb") }
        settings = SettingsStore(context, store)
    }

    @After fun closeIsolatedStore() {
        scope.cancel()
        runBlocking { scope.coroutineContext[Job]?.join() }
        if (::root.isInitialized) root.deleteRecursively()
    }

    // These bytes test file availability, not font parsing or a claimed Maple glyph shape.
    private fun localFile(id: String): File = File(context.filesDir, "fonts/$id.ttf").apply {
        parentFile!!.mkdirs()
        writeText("availability-fixture-$id")
    }

    @Test fun freshInstallUsesThePrimaryAndSystemCjkWithoutWritingDefaultKeys() = runBlocking {
        assertEquals("jetbrains_mono", settings.primaryFontId.first())
        assertEquals("", settings.fallbackFontId.first())
        val repaired = settings.repairUnavailableFontSelections()
        assertFalse(repaired.contains(primaryKey))
        assertFalse(repaired.contains(fallbackKey))
    }

    @Test fun missingOldFontsArePersistentlyRepairedWithoutResettingOtherData() = runBlocking {
        val kept = localFile("user_keep")
        settings.addUserFont(UserFont("user_keep", "Kept font"))
        settings.setPrimaryFont("noto_sans_sc")
        settings.setFallbackFont("maple_mono")
        settings.setFontSize(17.5f)
        settings.setLineSpacing(1.25f)
        settings.setLetterSpacing(0.8f)
        settings.setCursorStyle(2)
        settings.setColorScheme("custom_fixture")
        val before = store.data.first().asMap()
        // Effective values are valid even before the persistent repair finishes.
        assertEquals("jetbrains_mono", settings.primaryFontId.first())
        assertEquals("", settings.fallbackFontId.first())
        val after = settings.repairUnavailableFontSelections()
        assertEquals("jetbrains_mono", after[primaryKey])
        assertEquals("", after[fallbackKey])
        val fontKeys = setOf(primaryKey, fallbackKey)
        assertEquals(before.filterKeys { it !in fontKeys }, after.asMap().filterKeys { it !in fontKeys })
        assertEquals("availability-fixture-user_keep", kept.readText())
        assertEquals(listOf(UserFont("user_keep", "Kept font")), settings.userFonts.first())
    }

    @Test fun existingDownloadedAndImportedSelectionsAndFilesRemainUntouched() = runBlocking {
        val maple = localFile("maple_mono")
        val imported = localFile("user_keep")
        for ((primary, fallback) in listOf("maple_mono" to "user_keep", "user_keep" to "maple_mono")) {
            settings.setPrimaryFont(primary)
            settings.setFallbackFont(fallback)
            val before = store.data.first().asMap()
            assertEquals(before, settings.repairUnavailableFontSelections().asMap())
            assertEquals(primary, settings.primaryFontId.first())
            assertEquals(fallback, settings.fallbackFontId.first())
        }
        assertEquals("availability-fixture-maple_mono", maple.readText())
        assertEquals("availability-fixture-user_keep", imported.readText())
    }

    @Test fun oneMissingRoleDoesNotResetTheOtherAvailableRole() = runBlocking {
        localFile("maple_mono")
        settings.setPrimaryFont("maple_mono")
        settings.setFallbackFont("noto_sans_sc")
        var repaired = settings.repairUnavailableFontSelections()
        assertEquals("maple_mono", repaired[primaryKey])
        assertEquals("", repaired[fallbackKey])
        settings.setPrimaryFont("missing_font")
        settings.setFallbackFont("maple_mono")
        repaired = settings.repairUnavailableFontSelections()
        assertEquals("jetbrains_mono", repaired[primaryKey])
        assertEquals("maple_mono", repaired[fallbackKey])
    }

    @Test fun aRemainingLocalLegacyFontIsStillVisibleAndUsable() = runBlocking {
        localFile("noto_sans_sc")
        settings.setPrimaryFont("noto_sans_sc")
        settings.setFallbackFont("noto_sans_sc")
        val before = store.data.first().asMap()
        assertEquals(before, settings.repairUnavailableFontSelections().asMap())
        val fonts = FontRepository(context)
        assertTrue(fonts.isInstalled(FontCatalog.byId("noto_sans_sc")))
        assertTrue(FontCatalog.visibleFonts { fonts.fileFor(it).isFile }.any { it.id == "noto_sans_sc" })
    }

    @Test fun aDirectoryAtTheFontPathDoesNotCountAsAnInstalledFont() = runBlocking {
        val fonts = FontRepository(context)
        fonts.fileFor("maple_mono").mkdirs()
        assertFalse(fonts.isInstalled(FontCatalog.byId("maple_mono")))
        settings.setPrimaryFont("maple_mono")
        settings.setFallbackFont("maple_mono")
        val repaired = settings.repairUnavailableFontSelections()
        assertEquals("jetbrains_mono", repaired[primaryKey])
        assertEquals("", repaired[fallbackKey])
    }

    @Test fun restoringAppearanceDefaultsUsesSystemCjk() = runBlocking {
        settings.setPrimaryFont("missing_font")
        settings.setFallbackFont("maple_mono")
        settings.resetAppearanceDefaults()
        assertEquals("jetbrains_mono", settings.primaryFontId.first())
        assertEquals("", settings.fallbackFontId.first())
        assertEquals("", store.data.first()[fallbackKey])
    }

    @Test fun theDefaultChainStillCoversSystemCjkSymbolsAndPrivateIcons() {
        val fonts = FontRepository(context)
        val face = fonts.resolveTypeface("jetbrains_mono", "")
        val paint = Paint().apply { typeface = face; textSize = 32f }
        for (cp in listOf(0x4e2d, 0x6587, 0x6d4b, 0x8bd5, 0x23f5, 0x23f8, 0x2801, 0x28ff,
            0xe702, 0xf00c, 0xf015, 0xf07b, 0xf121)) {
            assertTrue("Default chain lacks U+${cp.toString(16)}", paint.hasGlyph(String(Character.toChars(cp))))
        }
        assertSame(face, fonts.resolveTypeface("noto_sans_sc", "maple_mono"))
    }
}
