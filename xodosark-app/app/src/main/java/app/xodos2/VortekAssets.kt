package app.xodos2

import android.content.Context
import app.xodos2.ui.runtime.NativeInstallCoordinator
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object VortekAssets {
    private const val ASSET_ROOT = "vortek"

    fun syncFromAssetsIfNeeded(context: Context) {
        synchronized(this) {
            val am = context.assets
            val filesDir = context.filesDir
            val vortekDir = File(filesDir, "vortek").apply { mkdirs() }

            try {
                val list = am.list(ASSET_ROOT) ?: emptyArray()
                for (file in list) {
                    val dest = File(vortekDir, file)
                    if (!dest.exists()) {
                        copyAssetFile(am, "$ASSET_ROOT/$file", dest)
                    }
                }
            } catch (_: IOException) { }
        }
    }

    fun installVortekToContainer(context: Context, containerId: Int) {
        syncFromAssetsIfNeeded(context)
        val filesDir = context.filesDir
        val vortekDir = File(filesDir, "vortek")
        val icdFile = File(vortekDir, "vortek_icd.aarch64.json")
        if (!icdFile.exists()) return

        val rootfs = NativeInstallCoordinator.containerPath(context, containerId)
        if (!rootfs.isDirectory) return

        val targetDir1 = File(rootfs, "usr/share/vulkan/icd.d").apply { mkdirs() }
        val targetDir2 = File(rootfs, "etc/vulkan/icd.d").apply { mkdirs() }

        try {
            icdFile.copyTo(File(targetDir1, "vortek_icd.aarch64.json"), overwrite = true)
            icdFile.copyTo(File(targetDir2, "vortek_icd.aarch64.json"), overwrite = true)
        } catch (_: Exception) { }
    }

    private fun copyAssetFile(am: android.content.res.AssetManager, path: String, out: File) {
        out.parentFile?.mkdirs()
        am.open(path).use { input ->
            FileOutputStream(out).use { input.copyTo(it) }
        }
    }
}
