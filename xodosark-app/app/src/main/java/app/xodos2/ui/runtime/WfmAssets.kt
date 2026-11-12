package app.xodos2.ui.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object WfmAssets {

    // Mirrors assets/wfm/**  ->  files/**  , overwriting every existing file.
    // Example:  assets/wfm/usr/opt/apps/wfm.exe  ->  files/usr/opt/apps/wfm.exe
    //
    // Skipped when files/usr/bin/wine is missing or a broken symlink,
    // so nothing runs before Wine is installed.  Safe to call every launch;
    // runs on Dispatchers.IO.
    suspend fun sync(context: Context) = withContext(Dispatchers.IO) {
        val wineLink = File(context.filesDir, "usr/bin/wine")
        if (!wineLink.exists()) return@withContext

        val top = try { context.assets.list("wfm") } catch (_: Exception) { null }
        if (top.isNullOrEmpty()) return@withContext

        fun copyFile(assetPath: String, destDir: File) {
            val out = File(destDir, assetPath.substringAfterLast('/'))
            try {
                context.assets.open(assetPath).use { i ->
                    out.outputStream().use { o -> i.copyTo(o) }
                }
                out.setReadable(true, false)
                val n = out.name.lowercase()
                if (n.endsWith(".exe") || n.endsWith(".dll") || n.endsWith(".bat")) {
                    out.setExecutable(true, false)
                }
            } catch (_: Exception) {}
        }

        fun walk(assetPath: String, destDir: File) {
            val children = try { context.assets.list(assetPath) } catch (_: Exception) { null } ?: return
            if (children.isEmpty()) {
                copyFile(assetPath, destDir)
            } else {
                val sub = File(destDir, assetPath.substringAfterLast('/')).apply { mkdirs() }
                for (c in children) walk("$assetPath/$c", sub)
            }
        }

        // assets/wfm/<child>  ->  files/<child>
        for (child in top) walk("wfm/$child", context.filesDir)
    }
}