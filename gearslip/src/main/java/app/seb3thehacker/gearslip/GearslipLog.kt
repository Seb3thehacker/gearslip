package app.seb3thehacker.gearslip

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Logs to logcat and to a file at once.
 *
 * The file copy is the safety net: entering USB accessory mode tears down adb-over-USB, so
 * if wireless adb drops in the car the file is the only surviving record of the run. It
 * lives under getExternalFilesDir, which `adb pull` can reach without root.
 */
object GearslipLog {

    const val TAG = "Gearslip"

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val io = Executors.newSingleThreadExecutor()

    private const val MAX_LIVE_LINES = 3000

    private var file: File? = null

    // What the live log screen shows. Bounded, and separate from the file, which always keeps
    // everything. The flow carries only a version number so a burst of lines costs the UI one
    // snapshot per frame rather than one list copy per line.
    private val live = ArrayDeque<String>()
    private val liveVersion = MutableStateFlow(0)

    val version: StateFlow<Int> = liveVersion

    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: return
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        file = File(dir, "gearslip-$name.log")
        i("log file: ${file?.absolutePath}")
    }

    fun snapshot(): List<String> = synchronized(live) { live.toList() }

    /** Clears the live view only. The log file on disk is left alone. */
    fun clearLive() {
        synchronized(live) { live.clear() }
        liveVersion.update { it + 1 }
    }

    /** The whole current session's file, for sharing. Falls back to the live buffer. */
    fun fullText(): String {
        flush()
        val f = file
        return if (f != null && f.isFile) f.readText() else snapshot().joinToString("\n")
    }

    fun i(message: String) = write("I", message)

    fun w(message: String) = write("W", message)

    fun e(message: String, t: Throwable? = null) {
        write("E", if (t == null) message else "$message: ${t.javaClass.simpleName}: ${t.message}")
        if (t != null) Log.e(TAG, message, t)
    }

    /** Unmissable in a scrolling logcat - this is what the whole spike exists to print. */
    fun verdict(headline: String, detail: String) {
        val bar = "=".repeat(60)
        i(bar)
        i("VERDICT: $headline")
        i(detail)
        i(bar)
        // The verdict is the one line the run exists to produce, and the app may be killed
        // the moment the accessory detaches. Do not leave it sitting in the write queue.
        flush()
    }

    /** Blocks until everything queued has actually reached the file. */
    fun flush(timeoutMs: Long = 2_000) {
        val latch = CountDownLatch(1)
        runCatching { io.execute { latch.countDown() } }.onFailure { return }
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    fun hex(label: String, data: ByteArray, limit: Int = 256) {
        val shown = minOf(data.size, limit)
        val sb = StringBuilder()
        for (idx in 0 until shown) {
            sb.append(String.format("%02X", data[idx]))
            if (idx % 16 == 15) sb.append('\n') else sb.append(' ')
        }
        val suffix = if (data.size > limit) "\n... (${data.size - limit} more bytes)" else ""
        i("$label (${data.size} bytes)\n$sb$suffix")
    }

    private fun write(level: String, message: String) {
        when (level) {
            "W" -> Log.w(TAG, message)
            "E" -> Log.e(TAG, message)
            else -> Log.i(TAG, message)
        }
        val line = "${stamp.format(Date())} $level $message"
        synchronized(live) {
            live.addLast(line)
            while (live.size > MAX_LIVE_LINES) live.removeFirst()
        }
        liveVersion.update { it + 1 }
        val target = file ?: return
        io.execute {
            runCatching { target.appendText(line + "\n") }
        }
    }
}
