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
        if (enabled) { if (!f.exists()) f.writeText("1") } else f.delete()
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

    // ── Startup defense ──────────────────────────────────────────────────
    /**
     * Called at app start, BEFORE NativeBridge.init() → Rust → PA spawn.
     *
     * Any PulseAudio alive at this point is an orphan from a previous crash
     * (a healthy PA never survives a clean app exit). Killing it releases
     * Android's AudioRecord handle *before* Rust spawns a fresh PA, which
     * prevents the "mic locked until reboot" state.
     *
     * No-op when there's no PA running → zero cold-start cost.
     */
    suspend fun startupCleanup() {
        val (alive, _) = sh("pgrep -x pulseaudio 2>/dev/null")
        if (alive == 0) {
            sh("pkill -9 -x pulseaudio 2>/dev/null")
            delay(500)
        }
    }

    // ── Source discovery ─────────────────────────────────────────────────
    suspend fun getSourceName(): String? {
        val (_, out) = sh("pactl list short sources 2>/dev/null")
        if (out.isEmpty()) return null
        val lines = out.lines()
        lines.firstOrNull { it.contains(SOURCE_NAME) }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.let { return it }
        lines.firstOrNull { it.contains("sles-source") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.let { return it }
        return null
    }

    // ── Hot apply / unload ──────────────────────────────────────────────
    /**
     * Loads the mic source and pins it as default.
     * 48 kHz preferred (native Android rate), 16 kHz fallback for ROMs that
     * reject 48 kHz (verified on Android 10: 16 kHz always works).
     */
    suspend fun hotApplyAndGetSource(enabled: Boolean): Pair<Boolean, String?> {
        if (!enabled) { safeUnload(); return true to null }
        val (exit, out) = sh(
            """
            if ! pactl list short sources 2>/dev/null | grep -q 'xodosark-mic'; then
                pactl load-module module-sles-source rate=48000 channels=1 source_name=xodosark-mic >/dev/null 2>&1 || \
                pactl load-module module-sles-source rate=16000 channels=1 source_name=xodosark-mic >/dev/null 2>&1 || \
                exit 1
            fi
            pactl set-default-source xodosark-mic >/dev/null 2>&1 || true
            pactl list short sources 2>/dev/null
            """.trimIndent()
        )
        val name = out.lines()
            .firstOrNull { it.contains(SOURCE_NAME) }?.split(Regex("\\s+"))?.getOrNull(1)
            ?: out.lines().firstOrNull { it.contains("sles-source") }
                ?.split(Regex("\\s+"))?.getOrNull(1)
        return (exit == 0) to name
    }

    suspend fun hotApply(enabled: Boolean): Boolean = hotApplyAndGetSource(enabled).first

    suspend fun safeUnload() {
        sh(
            "for id in $(pactl list short modules 2>/dev/null | awk '/sles-source/ {print \$1}'); " +
            "do pactl unload-module \$id 2>/dev/null; done"
        )
    }

    suspend fun pinDefaultSource() {
        repeat(3) {
            val (exit, _) = sh("pactl set-default-source $SOURCE_NAME 2>/dev/null")
            if (exit == 0) return
            delay(100)
        }
    }

    /**
     * Idle unload used by the ON_STOP watchdog.
     *
     * 1. Kills our own `parec` (VU meter) so it doesn't count as a client.
     * 2. If any non-parec source-output still exists, someone is recording →
     *    don't unload, so their stream isn't interrupted.
     * 3. Otherwise unload the mic module → releases Android's AudioRecord.
     */
    suspend fun safeUnloadIfIdle() {
        sh("pkill -9 parec 2>/dev/null")
        delay(150)
        if (countExternalRecorders() > 0) return
        safeUnload()
    }

    /**
     * Counts source-outputs that are NOT our own `parec` (VU meter).
     *
     * Must use **verbose** output — `pactl list short source-outputs` emits
     * one tab-separated line per entry with no headers and no
     * `application.name` property, so the previous split-by-"Source Output #"
     * approach silently returned 0 and never worked.
     */
    suspend fun countExternalRecorders(): Int {
        val (_, out) = sh("pactl list source-outputs 2>/dev/null")
        if (out.isBlank()) return 0
        val blocks = out.split("Source Output #").drop(1)
        return blocks.count { block ->
            !block.contains("application.name = \"parec\"")
        }
    }

    // ── VU level ─────────────────────────────────────────────────────────
    fun levelFlow(sourceName: String? = null): Flow<Float> = flow {
        val srcArg = sourceName?.let { "-d $it " } ?: ""
        val proc = ProcessBuilder(
            "/system/bin/sh", "-c",
            "exec parec $srcArg--format=s16le --rate=48000 --channels=1 --latency-msec=250 2>/dev/null"
        ).apply { environment().putAll(pactlEnv()) }
            .redirectErrorStream(false).start()

        try {
            val input = proc.inputStream
            val buf = ByteArray(9600)                 // 0.1 s @ 48 kHz mono s16
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