package com.briqt.moke.net

import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Only the generated fallback is sanitized; explicitly entered device names are not rewritten. */
internal fun defaultDeviceNameForModel(model: String): String {
    val sanitized = ("moke-" + model).lowercase(Locale.ROOT)
        .map { if (it.isLetterOrDigit() || it == '-') it else '-' }.joinToString("")
        .replace(Regex("-+"), "-").trim('-')
    var bytes = 0
    return sanitized.takeWhile { ch ->
        bytes += ch.toString().toByteArray(Charsets.UTF_8).size
        bytes <= 63
    }.trimEnd('-')
}

/**
 * Blocking runtime boundary. Only metadata lives behind [lock]; each network has its own IO gate.
 * The bridge and identity files are deliberately independent of Android so interleavings can be tested.
 */
internal class NetworkRuntime(
    private val bridge: Bridge,
    private val files: IdentityFiles,
    private val now: () -> Long,
    private val pause: () -> Unit,
) {
    data class Network(
        val id: String,
        val instanceId: String,
        val tailnet: Boolean,
        val configuration: String,
        // Non-secret identity binding; configuration is discarded after startup.
        val controlUrl: String = "",
    )
    data class Status(
        val state: String,
        val ips: List<String>,
        val tailnet: String,
        val loginName: String,
        val expired: Boolean,
        val everLoggedIn: Boolean,
        val leases: Int,
    )
    class Failure(val code: String, detail: String) : IOException(detail)

    interface Bridge {
        fun initialize()
        fun start(network: Network)
        fun stop(id: String)
        fun logout(id: String)
        fun status(id: String): Status
        fun leases(id: String): Int
        fun loginUrl(id: String): String
        fun sweepTickets()
    }

    interface IdentityFiles {
        fun hasLoggedIn(id: String): Boolean
        fun hasState(id: String): Boolean
        fun markLoggedIn(id: String)
        fun clearLoggedIn(id: String)
        fun deleteState(id: String)
    }

    private class Identity {
        var generation = 0L
        var loggedIn: Boolean? = null // null means not read, never "logged out".
        var markerSaved = false
        var expired = false
        var pendingDelete: Long? = null // the retired identity, not the next registration.
        var hold: Hold? = null
        // Survives idle Stop; only retiring/deleting this identity clears it.
        var controlUrl: String? = null
    }
    private class Hold(val attempt: Long?, val deadline: Long)
    private class Run(val network: Network, val generation: Long, var lastBusy: Long, var leases: Int = 0)
    internal class Deletion internal constructor(internal val generation: Long, internal val retired: Long?)

    private val lock = Any()
    private val initialization = Any()
    @Volatile private var initialized = false
    private val gates = ConcurrentHashMap<String, ReentrantLock>()
    private val identities = HashMap<String, Identity>()
    private val running = HashMap<String, Run>()

    private fun identity(id: String) = identities.getOrPut(id) { Identity() } // lock held
    private fun gate(id: String) = gates.getOrPut(id) { ReentrantLock() }
    private fun current(run: Run): Boolean = synchronized(lock) {
        val identity = identity(run.network.id)
        running[run.network.instanceId] === run && identity.generation == run.generation && identity.pendingDelete == null
    }
    private fun sameControl(run: Run, network: Network): Boolean =
        !network.tailnet || run.network.controlUrl == network.controlUrl
    private fun matchesBinding(identity: Identity, network: Network): Boolean =
        !network.tailnet || identity.controlUrl == null || identity.controlUrl == network.controlUrl
    private fun checkGeneration(network: Network, generation: Long) {
        if (!synchronized(lock) { identity(network.id).generation == generation }) cancelled()
    }
    private fun cancelled(): Nothing = throw Failure("CANCELLED", "network operation is no longer current")
    private fun controlChanged(): Nothing = throw Failure("CONTROL_CHANGED", "control server differs from this identity; use a new network or explicitly forget the old identity")

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(initialization) {
            if (!initialized) {
                bridge.initialize() // Never owns the metadata lock.
                initialized = true
            }
        }
    }

    fun isRunning(network: Network): Boolean = synchronized(lock) {
        running[network.instanceId]?.let { it.generation == identity(network.id).generation && sameControl(it, network) } == true
    }
    fun isKnownExpired(network: Network): Boolean = synchronized(lock) {
        identity(network.id).let { matchesBinding(it, network) && it.expired }
    }
    fun leases(network: Network): Int = synchronized(lock) {
        running[network.instanceId]?.takeIf { sameControl(it, network) }?.leases ?: 0
    }
    fun loggedInSnapshot(network: Network): Boolean? =
        if (!network.tailnet) false else synchronized(lock) {
            identity(network.id).let { if (matchesBinding(it, network)) it.loggedIn else false }
        }

    /** Initial marker IO is explicit, cached, and must be called off the main thread. */
    fun hasLoggedIn(network: Network): Boolean {
        if (!network.tailnet) return false
        loggedInSnapshot(network)?.let { return it }
        return gate(network.id).withLock {
            loggedInSnapshot(network)?.let { return@withLock it }
            val generation = synchronized(lock) { identity(network.id).generation }
            val present = files.hasLoggedIn(network.id)
            synchronized(lock) {
                val identity = identity(network.id)
                if (identity.generation == generation) {
                    identity.loggedIn = present
                    identity.markerSaved = present
                    if (present && identity.controlUrl == null) identity.controlUrl = network.controlUrl
                }
                identity.loggedIn == true
            }
        }
    }

    /** Install before dispatching a login Job, so a queued/cancelled Job cannot recreate its hold. */
    fun holdLogin(network: Network, attempt: Long) {
        synchronized(lock) { identity(network.id).hold = Hold(attempt, now() + LOGIN_HOLD_MS) }
    }
    fun endLogin(network: Network, attempt: Long? = null) {
        synchronized(lock) {
            val identity = identity(network.id)
            if (attempt != null && identity.hold?.attempt != attempt) return
            identity.hold = null
            running[network.instanceId]?.lastBusy = now()
        }
    }
    private fun checkHold(network: Network, hold: Hold) {
        if (!synchronized(lock) { identity(network.id).hold === hold }) cancelled()
    }

    fun beginLogin(network: Network, freshIdentity: Boolean, attempt: Long? = null): String {
        val hold = synchronized(lock) {
            val identity = identity(network.id)
            // Legacy callers have no request identity; they cannot replace a different
            // control binding or overwrite the explicit hold belonging to its login.
            if (attempt == null && !matchesBinding(identity, network)) controlChanged()
            if (attempt == null) Hold(null, now() + LOGIN_HOLD_MS).also { identity.hold = it }
            else identity.hold?.takeIf { it.attempt == attempt } ?: cancelled()
        }
        return gate(network.id).withLock {
            checkHold(network, hold)
            ensureInitialized()
            checkHold(network, hold)
            cleanupPending(network)
            checkHold(network, hold)
            val previous = synchronized(lock) { running[network.instanceId] }
            val changingControl = network.tailnet && synchronized(lock) {
                identity(network.id).controlUrl?.let { it != network.controlUrl } == true
            }
            if (changingControl) requireUnregisteredControlChange(network, previous, hold)
            checkHold(network, hold)
            if (freshIdentity || changingControl) {
                // Stop must succeed before changing generations or deleting state. A replaced
                // login hold survives retirement; a cancelled caller cannot release that hold.
                val generation = synchronized(lock) { identity(network.id).generation }
                previous?.let { if (!current(it)) cancelled(); stop(it) }
                synchronized(lock) {
                    val identity = identity(network.id)
                    if (identity.generation != generation || identity.pendingDelete != null) cancelled()
                    identity.generation++
                    identity.pendingDelete = generation
                    identity.loggedIn = false
                    identity.markerSaved = false
                    identity.expired = false
                }
                // Even if cancellation won during Stop, leave the old files retired for the
                // current request to clean. Never let a later start silently reuse those files.
                checkHold(network, hold)
                cleanupPending(network)
            }
            checkHold(network, hold)
            val run = start(network, allowAlreadyStarted = !freshIdentity && !changingControl)
            checkHold(network, hold)
            val url = bridge.loginUrl(network.instanceId)
            checkHold(network, hold)
            if (!current(run)) cancelled()
            url
        }
    }

    /** Only an explicit, still-current login may replace an observed unregistered node. */
    private fun requireUnregisteredControlChange(network: Network, previous: Run?, hold: Hold) {
        // After idle Stop we cannot inspect the frozen node's registration state. Keep its
        // binding and fail closed rather than erase a possibly registered identity.
        val run = previous ?: controlChanged()
        if (!current(run)) cancelled()
        if (bridge.leases(run.network.instanceId) > 0) throw Failure("BUSY", "network still carries connections")
        checkHold(network, hold)
        val status = bridge.status(run.network.instanceId)
        checkHold(network, hold)
        if (!current(run)) cancelled()
        val marker = files.hasLoggedIn(network.id)
        checkHold(network, hold)
        if (!current(run)) cancelled()
        val knownLoggedIn = synchronized(lock) { identity(network.id).let { it.loggedIn == true || it.markerSaved } }
        if (marker || knownLoggedIn || status.state != "NeedsLogin" || status.everLoggedIn || status.expired ||
            status.ips.isNotEmpty() || status.loginName.isNotEmpty() || status.tailnet.isNotEmpty()
        ) controlChanged()
    }

    fun ensureStarted(network: Network) { withStarted(network) {} }

    /** The IO gate spans startup, readiness AND acquisition of the Go-owned connection/relay lease. */
    fun <T> withStarted(network: Network, discard: (T) -> Unit = {}, action: () -> T): T {
        // An operation queued before retirement must not borrow the replacement's generation.
        val generation = synchronized(lock) { identity(network.id).generation }
        return gate(network.id).withLock {
            checkGeneration(network, generation)
            ensureInitialized()
            checkGeneration(network, generation)
            cleanupPending(network)
            checkGeneration(network, generation)
            val run = start(network)
            if (network.tailnet) awaitReady(run)
            if (!current(run)) cancelled()
            val value = action()
            if (!current(run)) {
                discard(value)
                cancelled()
            }
            refreshLeases(run)
            value
        }
    }

    private fun start(network: Network, allowAlreadyStarted: Boolean = true): Run {
        synchronized(lock) {
            if (!matchesBinding(identity(network.id), network)) controlChanged()
        }
        synchronized(lock) { running[network.instanceId] }?.let {
            if (!current(it)) cancelled()
            if (!sameControl(it, network)) controlChanged()
            synchronized(lock) { it.lastBusy = now() }
            return it // Hostname still changes only on the next actual startup, not a live connection.
        }
        val generation = synchronized(lock) { identity(network.id).generation }
        try {
            bridge.start(network)
        } catch (e: Failure) {
            if (e.code != "ALREADY_STARTED") throw e
            if (!allowAlreadyStarted) throw Failure("BUSY", "network is already running")
            // An untracked tailnet engine has no verified control binding to adopt.
            if (network.tailnet) controlChanged()
        }
        val run = Run(network.copy(configuration = ""), generation, now())
        // Even a startup invalidated by forget must be tracked until the engine has actually stopped.
        synchronized(lock) {
            running[network.instanceId] = run
            val identity = identity(network.id)
            if (network.tailnet && identity.generation == generation && identity.pendingDelete == null) {
                identity.controlUrl = network.controlUrl
            }
        }
        if (!current(run)) cancelled()
        return run
    }

    private fun awaitReady(run: Run) {
        val deadline = now() + READY_TIMEOUT_MS
        while (true) {
            val status = readStatus(run) ?: cancelled()
            when (status.state) {
                "Running" -> if (!status.expired) return else expired(run)
                "NeedsLogin" -> if (status.everLoggedIn) expired(run) else throw Failure("NEEDS_LOGIN", "node needs login")
                "NeedsMachineAuth" -> throw Failure("NEEDS_MACHINE_AUTH", "awaiting approval")
            }
            if (now() >= deadline) throw Failure("NOT_READY", "node is ${status.state}")
            pause()
        }
    }
    private fun expired(run: Run): Nothing {
        synchronized(lock) {
            if (current(run)) identity(run.network.id).expired = true
        }
        throw Failure("LOGIN_EXPIRED", "node key expired")
    }

    fun status(network: Network): Status? {
        if (!network.tailnet || !isRunning(network)) return null
        return gate(network.id).withLock {
            val run = synchronized(lock) { running[network.instanceId] } ?: return@withLock null
            if (!sameControl(run, network)) return@withLock null
            readStatus(run)
        }
    }
    private fun readStatus(run: Run): Status? {
        if (!current(run)) return null
        val status = bridge.status(run.network.instanceId)
        if (!current(run)) return null
        val persist = synchronized(lock) {
            val identity = identity(run.network.id)
            if (!current(run)) return null
            run.leases = status.leases
            if (status.leases > 0) run.lastBusy = now()
            if (status.state == "Running") {
                identity.expired = status.expired
                if (!status.expired) identity.loggedIn = true
            }
            status.state == "Running" && !status.expired && !identity.markerSaved
        }
        if (persist) {
            val saved = runCatching { files.markLoggedIn(run.network.id) }.isSuccess
            val stale = synchronized(lock) {
                if (current(run)) {
                    identity(run.network.id).markerSaved = saved
                    false
                } else true
            }
            // forget may invalidate while a filesystem write is blocked. The next identity cannot
            // start until this IO gate is released, so removing the late old marker is safe here.
            if (stale) { files.clearLoggedIn(run.network.id); return null }
        }
        return status.takeIf { current(run) }
    }

    /** First phase of forget: makes old status, marker reads and login attempts obsolete immediately. */
    fun invalidateIdentity(network: Network): Deletion = synchronized(lock) {
        val identity = identity(network.id)
        val retired = if (network.tailnet) identity.pendingDelete ?: identity.generation else null
        identity.generation++
        identity.loggedIn = false
        identity.markerSaved = false
        identity.expired = false
        identity.hold = null
        identity.pendingDelete = retired
        Deletion(identity.generation, retired)
    }

    fun forget(network: Network, deletion: Deletion = invalidateIdentity(network)): Boolean = gate(network.id).withLock {
        if (!synchronized(lock) { identity(network.id).generation == deletion.generation }) return@withLock true
        if (!network.tailnet) {
            val old = synchronized(lock) { running.values.filter { it.network.id == network.id && it.generation < deletion.generation } }
            old.forEach { runCatching { stop(it) } }
            return@withLock true
        }
        // A newer login may have acquired the gate first and already retired the old identity.
        if (!synchronized(lock) { identity(network.id).pendingDelete == deletion.retired }) return@withLock true
        files.clearLoggedIn(network.id)
        var run = synchronized(lock) { running[network.instanceId] }
        var loggedOut = true
        if (run == null && files.hasState(network.id)) {
            ensureInitialized()
            val temporary = Run(network, deletion.retired ?: 0, now())
            try {
                bridge.start(network)
                synchronized(lock) { running[network.instanceId] = temporary }
                run = temporary
            } catch (_: Exception) { loggedOut = false }
        }
        if (run != null) {
            loggedOut = runCatching { bridge.logout(network.instanceId) }.isSuccess && loggedOut
            if (runCatching { stop(run) }.isFailure) return@withLock loggedOut
        }
        deletePendingFiles(network)
        loggedOut
    }

    /** Gate held: stop succeeds before any state deletion; a BUSY failure leaves the retirement pending. */
    private fun cleanupPending(network: Network) {
        if (!network.tailnet || synchronized(lock) { identity(network.id).pendingDelete == null }) return
        synchronized(lock) { running[network.instanceId] }?.let { stop(it) }
        deletePendingFiles(network)
    }
    private fun deletePendingFiles(network: Network) {
        val retired = synchronized(lock) {
            if (running.containsKey(network.instanceId)) return
            identity(network.id).pendingDelete
        } ?: return
        files.clearLoggedIn(network.id)
        files.deleteState(network.id)
        synchronized(lock) {
            val identity = identity(network.id)
            if (identity.pendingDelete == retired) {
                identity.pendingDelete = null
                identity.controlUrl = null
            }
        }
    }
    private fun stop(run: Run) {
        bridge.stop(run.network.instanceId)
        synchronized(lock) { if (running[run.network.instanceId] === run) running.remove(run.network.instanceId) }
    }
    fun refreshLeases(network: Network): Int = gate(network.id).withLock {
        val run = synchronized(lock) { running[network.instanceId] } ?: return@withLock 0
        if (!sameControl(run, network)) return@withLock 0
        refreshLeases(run, touched = false)
        leases(network)
    }

    private fun refreshLeases(run: Run, touched: Boolean = true) {
        val count = runCatching { bridge.leases(run.network.instanceId) }.getOrNull() ?: return
        synchronized(lock) {
            if (running[run.network.instanceId] === run) {
                run.leases = count
                if (touched || count > 0) run.lastBusy = now()
            }
        }
    }

    /** Never queue behind another network's long operation. Re-check metadata after taking each gate. */
    fun reapIdle(dispatch: (() -> Unit) -> Unit = { it() }) {
        if (!initialized) return
        bridge.sweepTickets()
        val (candidates, retired) = synchronized(lock) {
            running.values.toList() to identities.filter { (id, identity) ->
                identity.pendingDelete != null && id !in running
            }.keys.toList()
        }
        candidates.forEach { run -> dispatch { runCatching { reap(run) } } }
        retired.forEach { id -> dispatch {
            val gate = gate(id)
            if (gate.tryLock()) {
                try { runCatching { deletePendingFiles(Network(id, id, true, "")) } } finally { gate.unlock() }
            }
        } }
    }

    private fun reap(run: Run) {
        val gate = gate(run.network.id)
        if (!gate.tryLock()) return
        try {
            val held = synchronized(lock) {
                running[run.network.instanceId] !== run || (identity(run.network.id).hold?.deadline ?: 0) > now()
            }
            if (held) return
            val count = runCatching { bridge.leases(run.network.instanceId) }.getOrNull() ?: return
            val idle = synchronized(lock) {
                run.leases = count
                if (count > 0) run.lastBusy = now()
                count == 0 && now() - run.lastBusy >= IDLE_STOP_MS
            }
            if (!idle) return
            // holdLogin/endLogin can run without this IO gate; validate their updates again.
            val stop = synchronized(lock) {
                (identity(run.network.id).hold?.deadline ?: 0) <= now() && now() - run.lastBusy >= IDLE_STOP_MS
            }
            if (stop && runCatching { stop(run) }.isSuccess) deletePendingFiles(run.network)
        } finally { gate.unlock() }
    }

    companion object {
        private const val IDLE_STOP_MS = 60_000L
        private const val READY_TIMEOUT_MS = 20_000L
        private const val LOGIN_HOLD_MS = 10 * 60_000L
    }
}
