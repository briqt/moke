package com.briqt.moke.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.briqt.moke.R
import com.briqt.moke.data.Host
import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.NetworkKind
import com.briqt.moke.data.StoreResult
import com.briqt.moke.net.NetworkException
import com.briqt.moke.net.TailnetDevice
import com.briqt.moke.ui.theme.MokeTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production Compose controls with a controlled metadata source; no network or saved user data. */
@RunWith(AndroidJUnit4::class)
class TailnetDevicePickerTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val network = NetworkEntry(id = "picker-test-network", name = "Test network", kind = NetworkKind.TAILSCALE)
    private val peer = TailnetDevice("picker-peer", "build-device", "build-device.tailnet.example", listOf("100.64.0.2"), "linux", true, true)

    @Test fun selectingDeviceInHostFormPreservesUsernameAndOnlySavesOnExplicitSave() {
        val saved = AtomicReference<Host?>()
        MokeActivityFixture.open().use { fixture ->
            fixture.onActivity { activity ->
                activity.setContent {
                    MokeTheme {
                        HostEditScreen(
                            initial = Host(username = "operator", networkId = network.id), allHosts = emptyList(),
                            onSave = { saved.set(it); StoreResult.Saved(it) }, onCancel = {}, networks = listOf(network),
                            loadDevices = { listOf(peer) },
                        )
                    }
                }
            }
            click(text(R.string.tailnet_choose_device))
            await("visible peer") { hasText(peer.name) }
            assertNull(saved.get())
            click(peer.name)
            await("selected target") { hasText(peer.address) }
            assertNull("Selecting a peer must not save or connect", saved.get())
            // The username may be below the IME-clipped viewport; the saved value is the contract.
            click(text(R.string.action_save))
            await("explicit save callback") { saved.get() != null }
            assertEquals(peer.address, saved.get()!!.host)
            assertEquals(network.id, saved.get()!!.networkId)
            assertEquals("operator", saved.get()!!.username)
        }
    }

    @Test fun searchEmptyStateAndCancelDoNotSelectADevice() {
        val selected = AtomicReference<TailnetDevice?>()
        withPicker(load = { listOf(peer) }, select = { selected.set(it) }) {
            await("visible peer") { hasText(peer.name) }
            setSearch("not-a-device")
            await("search empty state") { hasText(text(R.string.tailnet_devices_no_matches)) }
            assertNull(selected.get())
            click(text(R.string.tailnet_devices_clear_search), description = true)
            await("cleared search") { hasText(peer.name) }
            click(text(R.string.action_close), description = true)
            assertNull(selected.get())
        }
    }

    @Test fun failedRefreshCannotSelectStaleResultsAndRetryRecovers() {
        val calls = AtomicInteger()
        val selected = AtomicReference<TailnetDevice?>()
        withPicker(load = {
            if (calls.incrementAndGet() == 2) throw NetworkException("INTERNAL", network, "controlled failure")
            listOf(peer)
        }, select = { selected.set(it) }) {
            await("initial peer") { hasText(peer.name) }
            click(text(R.string.action_refresh), description = true)
            await("failed refresh") { hasText(text(R.string.tailnet_devices_stale)) }
            assertNull(selected.get())
            assertFalse("Stale rows must not be actionable", find(peer.name)?.let { clickable(it) } ?: false)
            click(text(R.string.action_retry))
            await("retry succeeded") { calls.get() == 3 && !hasText(text(R.string.tailnet_devices_stale)) }
            click(peer.name)
            await("fresh peer selected") { selected.get() == peer }
        }
    }

    @Test fun cancelledLoadCannotPopulateTheNextNetwork() {
        val current = mutableStateOf(network)
        val firstStarted = java.util.concurrent.CountDownLatch(1)
        val releaseFirst = java.util.concurrent.CountDownLatch(1)
        val next = network.copy(id = "picker-other-network", name = "Other network")
        val other = peer.copy(id = "other-peer", hostname = "other-device", dnsName = "other.tailnet.example")
        MokeActivityFixture.open().use { fixture ->
            fixture.onActivity { activity ->
                activity.setContent {
                    MokeTheme {
                        TailnetDevicePicker(current.value, load = { selected ->
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                if (selected.id == network.id) {
                                    firstStarted.countDown()
                                    check(releaseFirst.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                    listOf(peer)
                                } else listOf(other)
                            }
                        }, onSelect = {}, onOpenNetwork = {}, onDismiss = {})
                    }
                }
            }
            try {
                assertTrue(firstStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                fixture.onActivity { current.value = next }
                await("new network result") { hasText(other.name) }
                releaseFirst.countDown()
                inst.waitForIdleSync()
                assertFalse("Cancelled request must not replace the new network", hasText(peer.name))
            } finally { releaseFirst.countDown() }
        }
    }

    @Test fun emptyNetworkStillExplainsManualAddressEntry() {
        withPicker(load = { emptyList() }, select = { fail("Empty network cannot select a device") }) {
            await("honest empty state") { hasText(text(R.string.tailnet_devices_empty)) }
            assertTrue(find(text(R.string.tailnet_search_devices)) != null)
        }
    }

    @Test fun unregisteredNetworkOffersItsExistingLoginRecovery() {
        val opened = AtomicInteger()
        withPicker(load = { throw NetworkException("NEEDS_LOGIN", network, "controlled missing login") },
            select = { fail("Unregistered network cannot select a device") }, open = { opened.incrementAndGet() }) {
            val action = text(recoveryActionLabel(com.briqt.moke.net.RecoveryKind.LOGIN_NETWORK))
            await("login recovery action") { hasText(action) }
            click(action)
            assertEquals(1, opened.get())
        }
    }

    @Test fun offlineDevicesRemainSelectableAtLargeFontWithoutClaimingLoginAccess() {
        val selected = AtomicReference<TailnetDevice?>()
        val offline = peer.copy(online = false)
        withPicker(load = { listOf(offline) }, select = { selected.set(it) }, scale = 2f) {
            await("offline status") { hasText(text(R.string.tailnet_device_offline)) }
            assertTrue(hasText(peer.name))
            click(peer.name)
            await("offline selected") { selected.get() == offline }
        }
    }

    private fun withPicker(
        load: suspend (NetworkEntry) -> List<TailnetDevice>, select: (TailnetDevice) -> Unit,
        scale: Float = 1f, open: () -> Unit = {}, block: () -> Unit,
    ) {
        MokeActivityFixture.open().use { fixture ->
            val shown = mutableStateOf(true)
            fixture.onActivity { activity ->
                activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                        MokeTheme {
                            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                                if (shown.value) TailnetDevicePicker(network, load, select, open, { shown.value = false })
                            }
                        }
                    }
                }
            }
            block()
        }
    }

    private fun text(id: Int) = context.getString(id)
    private fun find(value: String, description: Boolean = false): AccessibilityNodeInfo? {
        val root = inst.uiAutomation.rootInActiveWindow ?: return null
        fun visit(node: AccessibilityNodeInfo, exact: Boolean): AccessibilityNodeInfo? {
            val content = (if (description) node.contentDescription else node.text)?.toString()
            if (if (description || exact) content == value else content?.contains(value) == true) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, exact)?.let { found -> return found } }
            return null
        }
        return visit(root, true) ?: if (description) null else visit(root, false)
    }
    private fun hasText(value: String) = find(value) != null
    private fun clickable(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            if (current?.isClickable == true) return current?.isEnabled == true
            current = current?.parent
        }
        return false
    }
    private fun click(value: String, description: Boolean = false) {
        await("control: $value") { find(value, description) != null }
        var node = find(value, description)!!
        repeat(6) {
            if (node.isClickable) {
                assertTrue("Control must be enabled: $value", node.isEnabled)
                assertTrue("Click rejected: $value", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                inst.waitForIdleSync()
                return
            }
            node = node.parent ?: throw AssertionError("No actionable parent: $value")
        }
        fail("No actionable parent: $value")
    }
    private fun setSearch(value: String) {
        fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { editable(it)?.let { found -> return found } }
            return null
        }
        val root = inst.uiAutomation.rootInActiveWindow ?: error("No window")
        val field = editable(root) ?: error("No search editor")
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        inst.waitForIdleSync()
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!predicate()) {
            if (SystemClock.uptimeMillis() >= deadline) throw AssertionError("Timed out: $label")
            SystemClock.sleep(50)
        }
    }
}
