package app.xodos2.ui.runtime

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.xodos2.NativeBridge
import app.xodos2.TerminalSessionIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

object IsoManager {

    private const val WFM_DOS_PATH = "Z:\\usr\\opt\\apps\\wfm.exe"
    private const val ISO_DIR      = "iso"
    private const val WINE_BIN     =
        "/data/data/app.xodos2/files/usr/opt/winece/arm64-v8a/bin/wine"

    fun winePrefix(context: Context): File  = File(context.filesDir, "home/.wine")
    fun isoDir(context: Context): File     = File(context.filesDir, ISO_DIR).apply { mkdirs() }
    fun wfmExe(context: Context): File     = File(context.filesDir, "usr/opt/apps/wfm.exe")
    fun userReg(context: Context): File    = File(winePrefix(context), "user.reg")

    // ── Asset sync: assets/wfm/usr/**  →  files/usr/**  (overwrite) ────
    /**
     * Copies every file under `assets/wfm/usr/` to the matching path under
     * `files/usr/`, **overwriting** whatever exists there.
     *
     *   assets/wfm/usr/opt/apps/wfm.exe      →  files/usr/opt/apps/wfm.exe
     *   assets/wfm/usr/opt/apps/libfoo.dll   →  files/usr/opt/apps/libfoo.dll
     *   assets/wfm/usr/opt/apps/skin/x.png   →  files/usr/opt/apps/skin/x.png
     *
     * Skipped entirely when `files/usr/bin/wine` is missing or a broken
     * symlink (File.exists() follows the link), so nothing runs before
     * Wine is installed.
     *
     * Idempotent — safe to call every launch. Runs on IO.
     */
    suspend fun syncWfmFromAssets(context: Context) = withContext(Dispatchers.IO) {
        val wineLink = File(context.filesDir, "usr/bin/wine")
        if (!wineLink.exists()) return@withContext   // Wine not installed (or link broken)

        val assetRoot = "wfm/usr"
        val top = try { context.assets.list(assetRoot) } catch (_: Exception) { null }
        if (top.isNullOrEmpty()) return@withContext

        val filesUsr = File(context.filesDir, "usr").apply { mkdirs() }

        fun copyFile(assetPath: String, destDir: File) {
            val out = File(destDir, assetPath.substringAfterLast('/'))
            try {
                // Open with truncate → existing file is fully replaced.
                context.assets.open(assetPath).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                out.setReadable(true, false)
                val n = out.name.lowercase()
                if (n.endsWith(".exe") || n.endsWith(".dll") || n.endsWith(".bat")) {
                    out.setExecutable(true, false)
                }
            } catch (_: Exception) { /* keep going — one bad file must not abort the sync */ }
        }

        fun walk(assetPath: String, destDir: File) {
            val children = try { context.assets.list(assetPath) } catch (_: Exception) { null }
                ?: return

            if (children.isEmpty()) {
                copyFile(assetPath, destDir)
            } else {
                val sub = File(destDir, assetPath.substringAfterLast('/')).apply { mkdirs() }
                for (c in children) walk("$assetPath/$c", sub)
            }
        }

        // assets/wfm/usr/<child> → files/usr/<child>
        for (child in top) walk("$assetRoot/$child", filesUsr)
    }

    // ── Terminal helper ────────────────────────────────────────────────
    private fun writeToWineTerminal(command: String) {
        val sid = TerminalSessionIds.WINE_TERMINAL
        if (!NativeBridge.isSessionAlive(sid)) {
            NativeBridge.spawnSessionInRootfs(
                sid, 80, 24,
                TerminalSessionIds.rootfsKindForNativeId(sid)
            )
        }
        NativeBridge.writeInput(sid, "$command\n".toByteArray())
    }

    // ── Registry read ──────────────────────────────────────────────────
    fun getMountedIsoDosPath(context: Context): String? {
        val f = userReg(context)
        if (!f.isFile) return null
        var inSection = false
        for (raw in f.readLines()) {
            val t = raw.trim()
            if (t.startsWith("[")) {
                inSection = t.startsWith("[Software\\\\Winlator\\\\WFM]")
                continue
            }
            if (inSection && t.startsWith("\"CurrentISOPath\"=")) {
                val v = t.substringAfter('=', "").trim().trim('"')
                return v.replace("\\\\", "\\")
            }
        }
        return null
    }

    // ── Mount / unmount ────────────────────────────────────────────────
    suspend fun mountIso(
        context: Context,
        uri: Uri,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            onProgress("Copying ISO…")
            val name = queryDisplayName(context, uri) ?: "disc.iso"
            val dest = File(isoDir(context), name)

            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext false

            onProgress("Registering with WFM…")
            val dosPath = "Z:\\$ISO_DIR\\${dest.name}"
            val escaped = dosPath.replace("\\", "\\\\").replace("'", "'\\''")

            writeToWineTerminal(
                "WINEPREFIX=\"\$HOME/.wine\" $WINE_BIN reg add " +
                "'HKEY_CURRENT_USER\\Software\\Winlator\\WFM' " +
                "/v 'CurrentISOPath' /t REG_SZ /d '$escaped' /f 2>/dev/null"
            )

            waitForRegistry(context, 4000)
            onProgress("Mounted: ${dest.name}")
            true
        } catch (e: Exception) {
            onProgress("Failed: ${e.message}")
            false
        }
    }

    suspend fun unmountIso(
        context: Context,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            onProgress("Unmounting…")
            writeToWineTerminal(
                "WINEPREFIX=\"\$HOME/.wine\" $WINE_BIN reg delete " +
                "'HKEY_CURRENT_USER\\Software\\Winlator\\WFM' " +
                "/v 'CurrentISOPath' /f 2>/dev/null || true"
            )
            waitForRegistry(context, 3000, expectNull = true)
            onProgress("Unmounted")
            true
        } catch (e: Exception) {
            onProgress("Failed: ${e.message}")
            false
        }
    }

    fun launchWfm() {
        writeToWineTerminal("xfex \"\$PREFIX/opt/apps/wfm.exe\" &")
    }

    // ── helpers ────────────────────────────────────────────────────────
    private suspend fun waitForRegistry(
        context: Context,
        timeoutMs: Long,
        expectNull: Boolean = false
    ): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            val cur = getMountedIsoDosPath(context)
            if (expectNull) { if (cur == null) return true }
            else            { if (cur != null) return true }
            delay(150)
        }
        return false
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    } catch (_: Exception) { null }
}