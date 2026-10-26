package com.briqt.moke.ui

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.briqt.moke.MainActivity
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Starts only the debuggable target's launcher; no test-APK bootstrap Activity is involved. */
internal class MokeActivityFixture private constructor(private val activity: MainActivity) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun <T> onActivity(action: (MainActivity) -> T): T {
        check(!closed.get()) { "Activity fixture is closed" }
        return onMain {
            check(!closed.get()) { "Activity fixture was closed before the action ran" }
            check(!activity.isFinishing && !activity.isDestroyed) { "Fixture Activity is no longer alive" }
            action(activity)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        onMain {
            // Never finish a different Activity, remove another task, or stop the instrumented process.
            if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
        }
    }

    companion object {
        private const val TASK_TIMEOUT_SECONDS = 5L
        private const val RESUME_TIMEOUT_MILLIS = 10_000L
        private val mainHandler = Handler(Looper.getMainLooper())
        private val qualifiedName = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")

        fun open(): MokeActivityFixture {
            checkTestThread()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                "Activity fixtures may launch only a debuggable target"
            }
            val packageName = context.packageName
            val className = MainActivity::class.java.name
            check(qualifiedName.matches(packageName) && qualifiedName.matches(className)) {
                "Invalid target package or Activity name"
            }
            val component = ComponentName(packageName, className)

            val existing = onMain {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                Stage.values().flatMap { monitor.getActivitiesInStage(it) }.filterIsInstance<MainActivity>().toSet()
            }
            val output = startTarget(instrumentation, component)
            check(output.lineSequence().none { it.trimStart().startsWith("Error:") || it.contains("SecurityException") }) {
                "The target launcher could not be started: ${output.take(1_000)}"
            }

            val deadline = SystemClock.uptimeMillis() + RESUME_TIMEOUT_MILLIS
            do {
                val resumed = onMain {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>()
                        .singleOrNull {
                            it !in existing && it.packageName == packageName && !it.isFinishing && !it.isDestroyed
                        }
                }
                if (resumed != null) return MokeActivityFixture(resumed)
                SystemClock.sleep(25)
            } while (SystemClock.uptimeMillis() < deadline)
            throw AssertionError("No new RESUMED MainActivity for $packageName after am start: ${output.take(1_000)}")
        }

        private fun startTarget(instrumentation: Instrumentation, component: ComponentName): String {
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // The only interpolated component is derived from our own validated target context.
            val command = "am start -a ${Intent.ACTION_MAIN} -c ${Intent.CATEGORY_LAUNCHER} " +
                "-f 0x${flags.toString(16)} -n ${component.flattenToString()}"
            val cancelled = AtomicBoolean(false)
            val descriptor = AtomicReference<ParcelFileDescriptor?>()
            val task = FutureTask(Callable {
                if (cancelled.get()) throw CancellationException("Target launch was cancelled")
                val opened = instrumentation.uiAutomation.executeShellCommand(command)
                descriptor.set(opened)
                if (cancelled.get()) {
                    descriptor.getAndSet(null)?.close()
                    throw CancellationException("Target launch was cancelled")
                }
                ParcelFileDescriptor.AutoCloseInputStream(opened).bufferedReader().use { it.readText() }
            })
            Thread(task, "moke-fixture-target-launch").apply { isDaemon = true; start() }
            try {
                return task.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (error: ExecutionException) {
                throw (error.cause ?: error)
            } catch (error: TimeoutException) {
                throw AssertionError("Timed out starting the debuggable target launcher", error)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AssertionError("Interrupted while starting the target launcher", error)
            } finally {
                cancelled.set(true)
                task.cancel(true)
                descriptor.getAndSet(null)?.close()
            }
        }

        private fun <T> onMain(action: () -> T): T {
            checkTestThread()
            val task = FutureTask(Callable { action() })
            check(mainHandler.post(task)) { "Main looper rejected the fixture action" }
            try {
                return task.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (error: ExecutionException) {
                throw (error.cause ?: error)
            } catch (error: TimeoutException) {
                throw AssertionError("Timed out waiting for a fixture main-thread action", error)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AssertionError("Interrupted while waiting for the main thread", error)
            } finally {
                // A timed-out task must not execute its user action when the main thread later recovers.
                task.cancel(false)
                mainHandler.removeCallbacks(task)
            }
        }

        private fun checkTestThread() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Call the Activity fixture from the test thread" }
        }
    }
}
