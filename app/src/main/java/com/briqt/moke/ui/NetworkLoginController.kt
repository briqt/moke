package com.briqt.moke.ui

import com.briqt.moke.data.NetworkEntry
import com.briqt.moke.data.StoreResult
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface NetworkLoginBackend {
    fun hold(entry: NetworkEntry, attempt: Long)
    fun begin(entry: NetworkEntry, authKey: String, attempt: Long): String
    fun end(entry: NetworkEntry, attempt: Long)
}

/** A request owns both its result and its hold; a cancelled request can own neither again. */
internal class NetworkLoginController(
    private val scope: CoroutineScope,
    private val save: suspend (NetworkEntry, Boolean) -> StoreResult<NetworkEntry>,
    private val backend: NetworkLoginBackend,
    private val describe: (Throwable) -> String,
) {
    private companion object {
        // The runtime outlives individual activity/view-model instances.
        val sequence = AtomicLong()
    }
    private val lock = Any()
    private val attempts = HashMap<String, Long>()
    private val entries = HashMap<String, NetworkEntry>()
    private val jobs = HashMap<String, Job>()
    private val _flows = MutableStateFlow<Map<String, MokeViewModel.LoginFlow>>(emptyMap())
    val flows: StateFlow<Map<String, MokeViewModel.LoginFlow>> = _flows.asStateFlow()
    private val _url = MutableStateFlow<MokeViewModel.LoginUrlRequest?>(null)
    val url: StateFlow<MokeViewModel.LoginUrlRequest?> = _url.asStateFlow()

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion {
            val owned = synchronized(lock) { entries.values.toList() }
            owned.forEach(::cancel)
        }
    }

    private fun current(id: String, attempt: Long): Boolean = synchronized(lock) { attempts[id] == attempt }

    suspend fun login(entry: NetworkEntry, authKey: String, editing: Boolean): StoreResult<NetworkEntry> {
        cancel(entry)
        val attempt = synchronized(lock) { sequence.incrementAndGet().also { attempts[entry.id] = it } }
        var held = false
        var started = false
        try {
            val result = save(entry, editing)
            if (result !is StoreResult.Saved) return result
            val saved = result.value
            synchronized(lock) {
                if (!scope.isActive || attempts[entry.id] != attempt) throw CancellationException("login superseded")
                // hold is metadata-only, so cancel cannot pass between owning the attempt and its hold.
                backend.hold(saved, attempt)
                held = true
                entries[saved.id] = saved
                _flows.update { it + (saved.id to MokeViewModel.LoginFlow(attempt = attempt)) }
            }
            val job = scope.launch {
                try {
                    val target = withContext(Dispatchers.IO) { backend.begin(saved, authKey.trim(), attempt) }
                    synchronized(lock) {
                        if (attempts[saved.id] == attempt) {
                            _flows.update { it + (saved.id to MokeViewModel.LoginFlow(url = target, busy = target.isEmpty(), attempt = attempt)) }
                            if (target.isNotEmpty()) _url.value = MokeViewModel.LoginUrlRequest(saved.id, attempt, target)
                        }
                    }
                } catch (e: CancellationException) {
                    complete(saved, attempt)
                    throw e
                } catch (t: Exception) {
                    val text = describe(t)
                    synchronized(lock) {
                        if (attempts[saved.id] == attempt) {
                            _flows.update { it + (saved.id to MokeViewModel.LoginFlow(error = text, busy = false, attempt = attempt)) }
                        }
                    }
                    backend.end(saved, attempt)
                } finally {
                    if (!current(saved.id, attempt)) backend.end(saved, attempt)
                }
            }
            job.invokeOnCompletion { if (it is CancellationException) complete(saved, attempt) }
            started = synchronized(lock) {
                if (attempts[saved.id] != attempt || job.isCancelled) false else { jobs[saved.id] = job; true }
            }
            if (!started) { job.cancel(); throw CancellationException("login superseded") }
            return result
        } finally {
            if (!started) {
                synchronized(lock) {
                    if (attempts[entry.id] == attempt) {
                        attempts.remove(entry.id)
                        entries.remove(entry.id)
                    }
                }
                if (held) backend.end(entry, attempt)
            }
        }
    }

    fun cancel(entry: NetworkEntry) {
        val (attempt, job, owner) = synchronized(lock) {
            val owned = Triple(attempts.remove(entry.id), jobs.remove(entry.id), entries.remove(entry.id) ?: entry)
            _flows.update { it - entry.id }
            _url.update { if (it?.networkId == entry.id) null else it }
            owned
        }
        job?.cancel()
        if (attempt != null) backend.end(owner, attempt)
    }

    fun complete(entry: NetworkEntry, attempt: Long) {
        val job = synchronized(lock) {
            if (attempts[entry.id] != attempt) return
            attempts.remove(entry.id)
            entries.remove(entry.id)
            _flows.update { if (it[entry.id]?.attempt == attempt) it - entry.id else it }
            _url.update { if (it?.networkId == entry.id && it.attempt == attempt) null else it }
            jobs.remove(entry.id)
        }
        backend.end(entry, attempt)
        job?.cancel()
    }

    fun reopen(id: String) = synchronized(lock) {
        val flow = _flows.value[id]
        if (flow != null && flow.url.isNotEmpty() && attempts[id] == flow.attempt) {
            _url.value = MokeViewModel.LoginUrlRequest(id, flow.attempt, flow.url)
        }
    }

    fun consume(request: MokeViewModel.LoginUrlRequest): String? = synchronized(lock) {
        if (!_url.compareAndSet(request, null)) null else request.url.takeIf { attempts[request.networkId] == request.attempt }
    }
}
