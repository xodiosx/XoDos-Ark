package app.xodos2.ui.runtime

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object AdrenotoolsDriverManager {

    private const val TAG = "AdrenotoolsMgr"
    private const val PREFS = "adrenotools"
    private const val PREF_LIST = "installed_drivers_json"
    private const val PREF_ACTIVE = "active_driver_name"

    /** What the guest shell sees as $PREFIX. */
    const val PREFIX_HOST = "/data/user/0/app.xodos2/files/usr"
    const val DRV_PATH_IN_CONTAINER = "$PREFIX_HOST/opt/drv"

    data class DriverMeta(
        val name: String,
        val author: String = "",
        val driverVersion: String = "",
        val libraryName: String = "",
        val description: String = "",
        val vendor: String = "",
        val packageVersion: String = "",
        val minApi: Int = 0,
    )

    data class InstalledDriver(val meta: DriverMeta, val zipPath: String)

    // ─────────── Paths ───────────

    private fun driversDir(context: Context) =
        File(context.filesDir, "usr/drivers").apply { mkdirs() }

    private fun adrenotoolsDir(context: Context) =
        File(context.filesDir, "usr/opt/adrenotools")

    private fun drvFile(context: Context) =
        File(context.filesDir, "usr/opt/drv")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun activeName(context: Context): String? =
        prefs(context).getString(PREF_ACTIVE, null)

    private fun setActiveName(context: Context, name: String?) {
        val e = prefs(context).edit()
        if (name.isNullOrBlank()) e.remove(PREF_ACTIVE) else e.putString(PREF_ACTIVE, name)
        e.apply()
    }

    // ─────────── List (read + prune) ───────────

    /** Reads prefs, drops any entry whose ZIP is gone, rewrites prefs if stale. */
    fun refreshAndList(context: Context): List<InstalledDriver> {
        val raw = prefs(context).getString(PREF_LIST, null) ?: return emptyList()
        val arr = try { JSONArray(raw) } catch (_: Exception) { return emptyList() }

        val kept = mutableListOf<InstalledDriver>()
        var pruned = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val zipPath = o.optString("zipPath")
            if (zipPath.isBlank() || !File(zipPath).isFile) { pruned++; continue }
            kept += InstalledDriver(
                DriverMeta(
                    name           = o.optString("name"),
                    author         = o.optString("author"),
                    driverVersion  = o.optString("driverVersion"),
                    libraryName    = o.optString("libraryName"),
                    description    = o.optString("description"),
                    vendor         = o.optString("vendor"),
                    packageVersion = o.optString("packageVersion"),
                    minApi         = o.optInt("minApi", 0),
                ),
                zipPath
            )
        }
        val sorted = kept.sortedBy { it.meta.name.lowercase() }
        if (pruned > 0) {
            Log.i(TAG, "Pruned $pruned stale driver entries")
            saveList(context, sorted)
        }
        return sorted
    }

    private fun saveList(context: Context, drivers: List<InstalledDriver>) {
        val arr = JSONArray()
        drivers.forEach { d ->
            arr.put(JSONObject().apply {
                put("name", d.meta.name)
                put("author", d.meta.author)
                put("driverVersion", d.meta.driverVersion)
                put("libraryName", d.meta.libraryName)
                put("description", d.meta.description)
                put("vendor", d.meta.vendor)
                put("packageVersion", d.meta.packageVersion)
                put("minApi", d.meta.minApi)
                put("zipPath", d.zipPath)
            })
        }
        prefs(context).edit().putString(PREF_LIST, arr.toString()).apply()
    }

    // ─────────── Install (copy zip + peek meta) ───────────

    suspend fun installFromUri(
        context: Context,
        uri: Uri,
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): Result<InstalledDriver> = withContext(Dispatchers.IO) {
        var tmpZip: File? = null
        try {
            onProgress(0, "Copying ZIP…")
            val stamp = System.currentTimeMillis()
            tmpZip = File(context.cacheDir, "adreno_$stamp.zip")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmpZip).use { out -> input.copyTo(out, 128 * 1024) }
            } ?: return@withContext Result.failure(Exception("Cannot open source"))

            onProgress(35, "Reading meta.json from ZIP…")
            val meta = readMetaFromZip(tmpZip)
                ?: return@withContext Result.failure(Exception("meta.json missing inside ZIP"))
            if (meta.name.isBlank())
                return@withContext Result.failure(Exception("meta.json: empty name"))
            if (meta.libraryName.isBlank())
                return@withContext Result.failure(Exception("meta.json: empty libraryName"))

            onProgress(70, "Saving ${meta.name}…")
            val destZip = File(driversDir(context), sanitizeFileName(meta.name) + ".zip")
            if (destZip.exists()) destZip.delete()
            if (!tmpZip.renameTo(destZip)) {
                tmpZip.copyTo(destZip, overwrite = true)
                tmpZip.delete()
            }

            val list = refreshAndList(context).toMutableList()
            list.removeAll { it.meta.name == meta.name }
            val entry = InstalledDriver(meta, destZip.absolutePath)
            list += entry
            saveList(context, list)

            onProgress(100, "Installed ${meta.name}")
            Result.success(entry)
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            Result.failure(e)
        } finally {
            tmpZip?.takeIf { it.exists() }?.delete()
        }
    }

    // ─────────── Uninstall ───────────

    fun uninstall(context: Context, name: String): Boolean {
        val list = refreshAndList(context).toMutableList()
        val entry = list.firstOrNull { it.meta.name == name } ?: return false
        runCatching { File(entry.zipPath).takeIf { it.exists() }?.delete() }
        list.removeAll { it.meta.name == name }
        saveList(context, list)
        if (activeName(context) == name) activateSystem(context)
        Log.i(TAG, "Uninstalled $name")
        return true
    }

    // ─────────── Activate custom driver ───────────

    /**
     * Wipes usr/opt/adrenotools, extracts the ZIP, reads meta.json from the
     * extracted folder to get the library name, writes usr/opt/drv.
     * Does NOT touch any terminal — caller sources the file.
     */
    suspend fun activate(
        context: Context,
        name: String,
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val entry = refreshAndList(context).firstOrNull { it.meta.name == name }
                ?: return@withContext Result.failure(Exception("Driver $name not installed"))
            val zip = File(entry.zipPath)
            if (!zip.isFile)
                return@withContext Result.failure(Exception("ZIP missing: ${entry.zipPath}"))

            // 1. Clean adrenotools folder
            val target = adrenotoolsDir(context)
            onProgress(10, "Cleaning adrenotools folder…")
            if (target.exists()) target.deleteRecursively()
            target.mkdirs()

            // 2. Extract
            onProgress(35, "Extracting $name…")
            unzip(zip, target)

            // 3. Read meta.json from the extracted folder (authoritative source)
            val metaFile = File(target, "meta.json")
            if (!metaFile.isFile)
                return@withContext Result.failure(Exception("meta.json missing after extract"))
            val meta = parseMeta(metaFile.readText())

            // 4. Write env
            onProgress(85, "Writing env…")
            writeDriverEnv(context, meta)
            setActiveName(context, meta.name)

            onProgress(100, "Activated ${meta.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "activate failed", e)
            Result.failure(e)
        }
    }

    // ─────────── Activate system driver ───────────

    fun activateSystem(context: Context): Boolean = try {
        val target = adrenotoolsDir(context)
        if (target.exists()) target.deleteRecursively()
        writeSystemEnv(context)
        setActiveName(context, null)
        Log.i(TAG, "System driver activated")
        true
    } catch (e: Exception) {
        Log.e(TAG, "activateSystem failed", e)
        false
    }

    // ─────────── Env file writers ───────────

    private fun writeDriverEnv(context: Context, meta: DriverMeta) {
        val contents = buildString {
            appendLine("# XoDos-Ark Adrenotools driver: ${meta.name}")
            appendLine("# Generated by AdrenotoolsDriverManager — do not edit")
            appendLine("export ADRENOTOOLS_DRIVER_PATH=\"$PREFIX_HOST/opt/adrenotools/\"")
            appendLine("export ADRENOTOOLS_DRIVER_NAME=\"${meta.libraryName}\"")
            appendLine("export ADRENOTOOLS_HOOKS_PATH=\"$PREFIX_HOST/lib/\"")
            appendLine()
            appendLine("export VK_ICD_FILENAMES=$PREFIX_HOST/share/vulkan/icd.d/wrapper_icd.aarch64.json")
            appendLine("export VK_DRIVER_FILES=$PREFIX_HOST/share/vulkan/icd.d/wrapper_icd.aarch64.json")
            appendLine()
            appendLine("# Performance / compatibility tweaks")
            appendLine("export MESA_VK_WSI_PRESENT_MODE=mailbox")
            appendLine("export TU_DEBUG=noconform")
            appendLine("export vblank_mode=0")
        }
        writeDrv(context, contents)
    }

    private fun writeSystemEnv(context: Context) {
        val contents = buildString {
            appendLine("# XoDos-Ark Adrenotools: system driver")
            appendLine("# Generated by AdrenotoolsDriverManager — do not edit")
            appendLine("unset ADRENOTOOLS_DRIVER_PATH")
            appendLine("unset ADRENOTOOLS_DRIVER_NAME")
            appendLine("unset ADRENOTOOLS_HOOKS_PATH")
            appendLine()
            appendLine("export VK_ICD_FILENAMES=$PREFIX_HOST/share/vulkan/icd.d/wrapper_icd.aarch64.json")
            appendLine("export VK_DRIVER_FILES=$PREFIX_HOST/share/vulkan/icd.d/wrapper_icd.aarch64.json")
            appendLine("export MESA_VK_WSI_PRESENT_MODE=mailbox")
            appendLine("export TU_DEBUG=noconform")
        }
        writeDrv(context, contents)
    }

    private fun writeDrv(context: Context, contents: String) {
        val f = drvFile(context)
        f.parentFile?.mkdirs()
        f.writeText(contents)
        f.setReadable(true, false)
        f.setWritable(true, false)
        f.setExecutable(false)
        Log.i(TAG, "Wrote ${f.absolutePath}")
    }

    // ─────────── ZIP helpers ───────────

    /** Reads meta.json from inside the ZIP without extracting to disk. */
    private fun readMetaFromZip(zip: File): DriverMeta? {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val name = e.name.replace('\\', '/').trimStart('/')
                    if (name == "meta.json") {
                        val bytes = zis.readBytes()
                        return parseMeta(String(bytes, Charsets.UTF_8))
                    }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return null
    }

    private fun parseMeta(json: String): DriverMeta {
        val o = JSONObject(json)
        return DriverMeta(
            name           = o.optString("name"),
            author         = o.optString("author"),
            driverVersion  = o.optString("driverVersion"),
            libraryName    = o.optString("libraryName"),
            description    = o.optString("description"),
            vendor         = o.optString("vendor"),
            packageVersion = o.optString("packageVersion"),
            minApi         = o.optInt("minApi", 0),
        )
    }

    private fun unzip(zip: File, destDir: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val safe = sanitize(e.name)
                if (safe != null) {
                    val out = File(destDir, safe)
                    if (e.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zis.copyTo(it, 128 * 1024) }
                    }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
    }

    private fun sanitize(raw: String): String? {
        var p = raw.replace('\\', '/').trim()
        while (p.startsWith("/")) p = p.substring(1)
        val parts = p.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) throw SecurityException("Unsafe zip entry: $raw")
        return parts.joinToString("/")
    }

    private fun sanitizeFileName(name: String) =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "driver" }
}