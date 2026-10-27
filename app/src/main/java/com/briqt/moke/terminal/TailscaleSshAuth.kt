package com.briqt.moke.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.method.AuthNone
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Browser approval is entirely server-driven. Opening a link is not a local authentication result. */
object TailscaleSshAuth {
    data class Request(val id: String, val target: String, val message: String, val url: String?)

    private val requests = TailscaleSshAuthRequests()
    val pending: StateFlow<List<Request>> = requests.pending

    /** Metadata only, safe on the UI thread. The attempt's worker closes its actual socket. */
    fun cancel(id: String) = requests.cancel(id)

    internal const val AUTH_TIMEOUT_MS = 300_000L

    internal fun authenticate(client: SSHClient, username: String, target: String) {
        val previousTimeout = client.transport.timeoutMs
        try {
            // SSHClient.auth reads this timeout for its promise. Never widen handshake/password timeouts.
            client.transport.timeoutMs = AUTH_TIMEOUT_MS.toInt()
            requests.authenticate(
                target = target,
                readBanner = { client.userAuth.banner },
                abort = { client.socket?.close() },
                authenticate = { client.auth(username, AuthNone()) },
            )
        } finally {
            client.transport.timeoutMs = previousTimeout
        }
    }
}

internal enum class TailscaleSshAuthFailureKind { CANCELLED, TIMED_OUT, REJECTED }

/** Safe, localized authentication failure; browsing must not silently launch another approval. */
internal class TailscaleSshAuthException(val kind: TailscaleSshAuthFailureKind, message: String) : IOException(message)

/** Deliberately no original exception/cause: a server disconnect message may contain a secret URL. */
internal class TailscaleSshAuthFailure(val kind: TailscaleSshAuthFailureKind, val detail: String) :
    IOException("Tailscale SSH authentication: ${kind.name}")

/** Each authentication, including simultaneous attempts to the same host, owns its own ticket/thread. */
internal class TailscaleSshAuthRequests {
    private class Attempt(val target: String) {
        val id: String = UUID.randomUUID().toString()
        val wake = CountDownLatch(1)
        @Volatile var finished = false
        @Volatile var stopped: TailscaleSshAuthFailureKind? = null
        @Volatile var interruptedByObserver = false
        var request: TailscaleSshAuth.Request? = null
    }

    private val lock = Any()
    private val attempts = linkedMapOf<String, Attempt>()
    private val mutablePending = MutableStateFlow<List<TailscaleSshAuth.Request>>(emptyList())
    val pending: StateFlow<List<TailscaleSshAuth.Request>> = mutablePending.asStateFlow()

    fun cancel(id: String) {
        synchronized(lock) {
            attempts[id]?.let { stopLocked(it, TailscaleSshAuthFailureKind.CANCELLED) }
        }
    }

    /** Called synchronously on the connection worker; only the observer performs cancellation I/O. */
    fun authenticate(
        target: String,
        readBanner: () -> String,
        abort: () -> Unit,
        timeoutMs: Long = TailscaleSshAuth.AUTH_TIMEOUT_MS,
        pollMs: Long = 100L,
        authenticate: () -> Unit,
    ) {
        require(timeoutMs > 0 && pollMs > 0)
        val attempt = Attempt(TailscaleSshAuthText.target(target))
        val started = System.nanoTime()
        val authWorker = Thread.currentThread()
        val timeout = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(lock) { attempts[attempt.id] = attempt }
        val observer = Thread({
            try {
                var previous: String? = null
                while (!attempt.finished) {
                    val left = timeout - (System.nanoTime() - started)
                    if (left <= 0) stop(attempt, TailscaleSshAuthFailureKind.TIMED_OUT)
                    if (attempt.stopped != null) {
                        // Close before sshj disconnect: DISCONNECT itself can block behind a transport write.
                        runCatching(abort)
                        // Also wake a promise waiter if the socket already failed or close threw.
                        // Serialize against finish so a late cancellation cannot interrupt later work.
                        synchronized(lock) {
                            if (!attempt.finished) {
                                attempt.interruptedByObserver = true
                                authWorker.interrupt()
                            }
                        }
                        return@Thread
                    }
                    val banner = runCatching(readBanner).getOrDefault("")
                    if (banner != previous) {
                        publish(attempt, banner)
                        previous = banner
                    }
                    attempt.wake.await(minOf(TimeUnit.MILLISECONDS.toNanos(pollMs), left.coerceAtLeast(1)), TimeUnit.NANOSECONDS)
                }
            } catch (_: InterruptedException) {
                // Normal completion interrupts this bounded, lifecycle-owned observation only.
            }
        }, "moke-tssh-check-${attempt.id}").apply { isDaemon = true }
        var failure: Throwable? = null
        var reason: TailscaleSshAuthFailureKind?
        var detail = ""
        try {
            observer.start()
            authenticate()
        } catch (t: Throwable) {
            failure = t
        } finally {
            // Preserve external interrupts, but do not leak our own abort wake-up into later caller work.
            val externallyInterrupted = Thread.currentThread().isInterrupted && !attempt.interruptedByObserver
            // Capture the last banner even if a refusal completed before the observer's first poll.
            detail = TailscaleSshAuthText.banner(runCatching(readBanner).getOrDefault("")).message
            when {
                Thread.currentThread().isInterrupted -> stop(attempt, TailscaleSshAuthFailureKind.CANCELLED)
                timedOut(failure) || System.nanoTime() - started >= timeout -> stop(attempt, TailscaleSshAuthFailureKind.TIMED_OUT)
            }
            reason = synchronized(lock) {
                attempt.finished = true
                attempts.remove(attempt.id)
                publishPendingLocked()
                attempt.wake.countDown()
                attempt.stopped
            }
            observer.interrupt()
            // No monitor survives normal completion; bound cleanup even if the socket implementation stalls.
            var interrupted = Thread.interrupted() && !attempt.interruptedByObserver || externallyInterrupted
            try { observer.join(1_000) } catch (_: InterruptedException) { interrupted = true }
            finally { if (interrupted) Thread.currentThread().interrupt() }
        }
        reason?.let { throw TailscaleSshAuthFailure(it, "") }
        failure?.let {
            if (it is Error) throw it
            throw TailscaleSshAuthFailure(TailscaleSshAuthFailureKind.REJECTED, detail)
        }
    }

    private fun publish(attempt: Attempt, raw: String) {
        val banner = TailscaleSshAuthText.banner(raw)
        synchronized(lock) {
            if (attempt.finished || attempt.stopped != null) return
            attempt.request = if (banner.message.isBlank() && banner.url == null) null else
                TailscaleSshAuth.Request(attempt.id, attempt.target, banner.message, banner.url)
            publishPendingLocked()
        }
    }

    private fun stop(attempt: Attempt, reason: TailscaleSshAuthFailureKind) = synchronized(lock) {
        if (!attempt.finished) stopLocked(attempt, reason)
    }

    private fun stopLocked(attempt: Attempt, reason: TailscaleSshAuthFailureKind) {
        if (attempt.stopped != null) return
        attempt.stopped = reason
        attempt.request = null
        publishPendingLocked()
        attempt.wake.countDown()
    }

    private fun publishPendingLocked() {
        mutablePending.value = attempts.values.mapNotNull { it.request }
    }

    private fun timedOut(failure: Throwable?): Boolean {
        var cause = failure
        repeat(8) {
            if (cause is TimeoutException) return true
            cause = cause?.cause ?: return false
        }
        return false
    }
}
