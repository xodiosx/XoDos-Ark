package app.xodos2.ui.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

object MicManager {

    private const val FLAG_FILE = "mic_enabled"
    private const val SOURCE_NAME = "xodosark-mic"
    private const val NATIVE_BIN = "/data/data/app.xodos2/files/usr/bin"

    fun flagFile(context: Context): File = File(context.filesDir, FLAG_FILE)

    fun isEnabled(context: Context): Boolean = flagFile(context).exists()

    fun setEnabled(context: Context, enabled: Boolean) {
        val f = flagFile(context)
        if (enabled) {
            if (!f.exists()) f.writeText("1")
        } else {
            f.delete()
        }
    }

    private fun pactlEnv(): Map<String, String> = mapOf(
        "PULSE_SERVER" to "tcp:127.0.0.1",
        "PATH" to "$NATIVE_BIN:/system/bin:/system/xbin"
    )

    private suspend fun sh(cmd: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder("/system/bin/sh", "-c", cmd)
                .apply { environment().putAll(pactlEnv()) }
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            p.waitFor() to out
        } catch (_: Exception) { -1 to "" }
    }

    suspend fun getSourceName(): String? {
        val (_, out) = sh("pactl list short sources 2>/dev/null")
        if (out.isEmpty()) return null
        val lines = out.lines()
        lines.firstOrNull { it.contains(SOURCE_NAME) }
            ?.split(Regex("\\s+"))?.getOrNull(1)
            ?.let { return it }
        lines.firstOrNull { it.contains("sles-source") }
            ?.split(Regex("\\s+"))?.getOrNull(1)
            ?.let { return it }
        return null
    }

    suspend fun hotApply(enabled: Boolean): Boolean {
        return if (enabled) {
            if (getSourceName() != null) return true
            val (exit, _) = sh(
                "pactl load-module module-sles-source rate=16000 channels=1 source_name=$SOURCE_NAME 2>&1"
            )
            if (exit == 0) {
                pinDefaultSource()
                true
            } else false
        } else {
            safeUnload()
            true
        }
    }

    /**
     * Pin the mic as PulseAudio's default source.
     * Max wait: 3 tries × (100 ms delay + ~50 ms sh) = ~450 ms worst case,
     * ~50 ms typical (first try succeeds).
     */
    suspend fun pinDefaultSource() {
        repeat(3) {
            val (exit, _) = sh("pactl set-default-source $SOURCE_NAME 2>/dev/null")
            if (exit == 0) return
            delay(100)
        }
    }

    /** Unload every sles-source module. No-op if none is loaded. */
    suspend fun safeUnload() {
        val (_, out) = sh("pactl list short modules 2>/dev/null")
        val ids = out.lines()
            .filter { it.contains("sles-source") }
            .mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull() }
        for (id in ids) sh("pactl unload-module $id 2>/dev/null")
    }

    /** Background-safe unload — used by the 5 s watcher in AppScreen. */
    suspend fun safeUnloadIfIdle() {
        sh("pkill -9 parec 2>/dev/null")
        delay(150)
        val (_, out) = sh("pactl list short source-outputs 2>/dev/null")
        if (out.isNotBlank()) return
        safeUnload()
    }

    fun levelFlow(sourceName: String? = null): Flow<Float> = flow {
        val srcArg = sourceName?.let { "-d $it " } ?: ""
        val proc = ProcessBuilder(
            "/system/bin/sh", "-c",
            "exec parec $srcArg--format=s16le --rate=8000 --channels=1 --latency-msec=100 2>/dev/null"
        ).apply { environment().putAll(pactlEnv()) }
            .redirectErrorStream(false).start()

        try {
            val input = proc.inputStream
            val buf = ByteArray(1600)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                var sumSq = 0.0
                var cnt = 0
                var i = 0
                while (i + 1 < n) {
                    val s = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
                    sumSq += s.toDouble() * s
                    cnt++
                    i += 2
                }
                if (cnt > 0) {
                    val rms = Math.sqrt(sumSq / cnt)
                    emit((rms / 32768.0).toFloat().coerceIn(0f, 1f))
                }
            }
        } finally {
            try { proc.destroy() } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)
}