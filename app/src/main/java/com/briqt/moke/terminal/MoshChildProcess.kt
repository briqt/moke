package com.briqt.moke.terminal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Only the native process boundary is replaceable in JVM tests, not mosh's protocol. */
internal interface MoshProcessOps {
    fun waitFor(pid: Int): Int
    fun kill(pid: Int)
    fun closeFd(fd: Int)
}

/** A positive pid has one reaper before any descriptor wrapping or other fallible initialization. */
internal class MoshChildProcess private constructor(
    private val pid: Int,
    fd: Int,
    private val ops: MoshProcessOps,
    private val onForced: () -> Unit,
    private val onExit: (Int) -> Unit,
) {
    private val lock = Any()
    private var rawFd = fd
    private var descriptor: AutoCloseable? = null
    private val reaping = AtomicBoolean()
    private val forced = AtomicBoolean()
    private val exit = CountDownLatch(1)
    @Volatile var exited = false
        private set
    val fd: Int get() = synchronized(lock) { rawFd }

    /** adoptFd is local and nonblocking. Serialize transfer against the waiter closing the raw fd. */
    fun <T : AutoCloseable> adopt(factory: (Int) -> T): T = synchronized(lock) {
        check(!exited && rawFd >= 0 && descriptor == null) { "mosh PTY already closed or adopted" }
        factory(rawFd).also { descriptor = it }
    }

    /** Keep a local ioctl from racing fd close/reuse; the action must not wait or perform network I/O. */
    fun withFd(action: (Int) -> Unit) = synchronized(lock) {
        if (!exited && rawFd >= 0) action(rawFd)
    }

    fun awaitExit(timeoutMs: Long): Boolean = exit.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun forceClose() {
        if (!forced.compareAndSet(false, true)) return
        synchronized(lock) {
            if (!exited) {
                // This callback only schedules remote cleanup; mark it before kill can wake the reaper.
                runCatching(onForced)
                runCatching { ops.kill(pid) }
            }
            closeFdLocked()
        }
    }

    private fun closeFdLocked() {
        if (rawFd < 0) return
        val fd = rawFd
        rawFd = -1
        val owner = descriptor
        descriptor = null
        if (owner != null) runCatching { owner.close() } else runCatching { ops.closeFd(fd) }
    }

    private fun reap() {
        if (!reaping.compareAndSet(false, true)) return
        val code = try { ops.waitFor(pid) } catch (_: Throwable) { 1 }
        synchronized(lock) {
            exited = true
            closeFdLocked()
        }
        exit.countDown()
        onExit(code)
    }

    companion object {
        fun start(
            pid: Int,
            fd: Int,
            ops: MoshProcessOps,
            onForced: () -> Unit,
            launch: (() -> Unit) -> Unit = { task -> Thread(task, "moke-mosh-waiter").start() },
            onExit: (Int) -> Unit,
        ): MoshChildProcess {
            if (pid <= 0) {
                if (fd >= 0) runCatching { ops.closeFd(fd) }
                error("mosh subprocess did not return a positive pid")
            }
            val child = MoshChildProcess(pid, fd, ops, onForced, onExit)
            try {
                launch { child.reap() }
            } catch (t: Throwable) {
                // The creator is already a worker: even a failed Thread.start cannot strand its child.
                child.forceClose()
                child.reap()
                throw t
            }
            return child
        }
    }
}

/** A numeric PID alone is never authority to clean up a remote server. */
internal data class MoshServerIdentity(
    val pid: Int,
    val uid: Long,
    val bootId: String,
    val startedTicks: Long,
    val nonce: String,
) {
    init {
        require(pid > 1 && uid >= 0 && startedTicks > 0)
        require(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(bootId))
        require(Regex("[a-f0-9]{32}").matches(nonce))
    }
}

/** Bootstrap can return after close; ownership of that late witnessed server is taken once. */
internal class MoshServerProcess(private val kill: (MoshServerIdentity) -> Unit) {
    private val lock = Any()
    private var identity: MoshServerIdentity? = null
    private var cleanupRequested = false
    private var disarmed = false
    private var registered = false

    fun register(value: MoshServerIdentity?) {
        val orphan = synchronized(lock) {
            if (registered || disarmed) null
            else {
                registered = true
                if (cleanupRequested) value else { identity = value; null }
            }
        }
        orphan?.let(kill)
    }

    fun cleanup() {
        val orphan = synchronized(lock) {
            cleanupRequested = true
            identity.also { identity = null }
        }
        orphan?.let(kill)
    }

    fun disarm() = synchronized(lock) { disarmed = true; identity = null }
}

/** No installation, files, environ reads, numeric kill or weaker fallback on unsupported hosts. */
internal object MoshOrphanGuard {
    private const val PREFIX = "__MOKE_MOSH_INSTANCE_V1__|"
    private val noncePattern = Regex("[a-f0-9]{32}")
    private fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"

    /** Insert the inert argv witness before the existing optional -- carrier; Bootstrap owns its syntax. */
    fun serverCommand(startupCommand: String?, instanceNonce: String): String {
        require(noncePattern.matches(instanceNonce))
        val base = MoshBootstrap.serverCommand()
        val original = MoshBootstrap.serverCommand(startupCommand = startupCommand)
        check(original.startsWith(base))
        return base + " -l MOKE_MOSH_INSTANCE=$instanceNonce" + original.removePrefix(base)
    }

    // Open the pidfd BEFORE reading /proc. A reuse before capture is rejected by the independent
    // argv nonce; a reuse after capture is rejected by birth identity and cannot retarget the pidfd.
    private val witness = """
        import os, signal, sys
        pid = int(sys.argv[1])
        nonce = sys.argv[2]
        if pid <= 1 or len(nonce) != 32 or any(c not in 'abcdef0123456789' for c in nonce):
            sys.exit(0)
        if not hasattr(os, 'pidfd_open') or not hasattr(signal, 'pidfd_send_signal'):
            sys.exit(0)
        fd = None
        try:
            fd = os.pidfd_open(pid, 0)
            def witness():
                uid = os.getuid()
                if uid != os.geteuid() or os.stat('/proc/%d' % pid).st_uid != uid:
                    raise ValueError('uid')
                with open('/proc/sys/kernel/random/boot_id', 'r', encoding='ascii') as f:
                    boot = f.read(128).strip()
                with open('/proc/%d/comm' % pid, 'rb') as f:
                    if f.read(128) != b'mosh-server\n':
                        raise ValueError('comm')
                with open('/proc/%d/cmdline' % pid, 'rb') as f:
                    argv = f.read(65536).split(b'\0')
                marker = ('MOKE_MOSH_INSTANCE=' + nonce).encode('ascii')
                if not any(a == b'-l' and b == marker for a, b in zip(argv, argv[1:])):
                    raise ValueError('nonce')
                with open('/proc/%d/stat' % pid, 'rb') as f:
                    stat = f.read(65536)
                head, tail = stat.rsplit(b') ', 1)
                if head != str(pid).encode('ascii') + b' (mosh-server':
                    raise ValueError('stat comm')
                ticks = int(tail.split()[19])
                if ticks <= 0:
                    raise ValueError('birth')
                return uid, boot, ticks
            actual = witness()
    """.trimIndent()

    private fun python(body: String, vararg args: String): String =
        "python3 -I -c ${q(body)}" + args.joinToString("") { " ${q(it)}" } + " 2>/dev/null"

    fun captureCommand(pid: Int, nonce: String): String {
        require(pid > 1 && noncePattern.matches(nonce))
        return python(witness + "\n" + """
                if actual == witness():
                    signal.pidfd_send_signal(fd, 0, None, 0)
                    print('$PREFIX%d|%d|%s|%d|%s' % (pid, actual[0], actual[1], actual[2], nonce))
            except (OSError, ValueError, IndexError):
                pass
            finally:
                if fd is not None:
                    os.close(fd)
        """.trimIndent(), pid.toString(), nonce)
    }

    fun parseCapture(output: String, pid: Int, nonce: String): MoshServerIdentity? {
        val lines = output.replace("\r\n", "\n").split('\n')
        val records = lines.dropLast(1).filter { it.startsWith(PREFIX) }
        if (records.size != 1 || lines.last().startsWith(PREFIX)) return null
        val fields = records.single().removePrefix(PREFIX).split('|')
        if (fields.size != 5 || fields[0] != pid.toString() || fields[4] != nonce) return null
        return runCatching {
            MoshServerIdentity(pid, fields[1].toLong(), fields[2], fields[3].toLong(), nonce)
        }.getOrNull()
    }

    fun cleanupCommand(identity: MoshServerIdentity): String = python(witness + "\n" + """
                expected = (int(sys.argv[3]), sys.argv[4], int(sys.argv[5]))
                if actual == expected and witness() == expected:
                    signal.pidfd_send_signal(fd, signal.SIGTERM, None, 0)
            except (OSError, ValueError, IndexError):
                pass
            finally:
                if fd is not None:
                    os.close(fd)
        """.trimIndent(), identity.pid.toString(), identity.nonce, identity.uid.toString(), identity.bootId, identity.startedTicks.toString())
}
