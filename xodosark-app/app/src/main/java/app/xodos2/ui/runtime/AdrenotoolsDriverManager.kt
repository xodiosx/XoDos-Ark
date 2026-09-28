package app.xodos2.ui.runtime

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Manages Adrenotools-style GPU driver packages and is the ONLY writer of
 * <filesDir>/usr/opt/drv.
 *
 * drv file structure (written in this order):
 *   1) Adrenotools block — ADRENOTOOLS_* exports and wrapper VK ICD, or unset block
 *      for the system driver.
 *   2) Graphics block   — pulled from DisplayOrchestrator.buildNativeGraphicsEnv(),
 *      which reflects the user's current OpenGL/Vulkan selection.
 *
 * Storage layout (native side):
 *   <filesDir>/usr/drivers/<name>.zip          – original user-picked ZIP
 *   <filesDir>/usr/opt/adrenotools/            – extracted contents of the active driver
 *   <filesDir>/usr/opt/drv                     – merged env file sourced by nt / xrun
 *
 * List & state live in SharedPreferences("adrenotools"):
 *   "installed_drivers_json" – JSON array of {name, ..., zipPath}
 *   "active_driver_name"     – absent = system driver
 */
object AdrenotoolsDriverManager {

    private const val TAG = "AdrenotoolsMgr"
    private const val PREFS = "adrenotools"
    private const val PREF_LIST = "installed_drivers_json"
    private const val PREF_ACTIVE = "active_driver_name"

    /** Host-side prefix (matches PREFIX inside the guest wrapper). */
    const val PREFIX_HOST = "/data/user/0/app.xodos2/files/usr"

    /** Path that the drawer sources in the terminal. */
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

    /**
     * Reads the JSON list from SharedPreferences, drops entries whose ZIP has
     * been deleted out from under us, and rewrites the XML if anything was stale.
     */
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

    /**
     * Copies the picked ZIP into app-private storage and reads meta.json from
     * inside the archive (without extracting to disk). No drv write here —
     * installation never changes what's active.
     */
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

    /**
     * Removes the ZIP, drops the list entry, and — if the removed driver was
     * active — reverts to the system driver and rewrites usr/opt/drv with the
     * merged (system + graphics) content.
     *
     * Pass [prefs] so the graphics half of the drv file can be rebuilt.
     */
    fun uninstall(context: Context, prefs: SharedPreferences, name: String): Boolean {
        val list = refreshAndList(context).toMutableList()
        val entry = list.firstOrNull { it.meta.name == name } ?: return false
        runCatching { File(entry.zipPath).takeIf { it.exists() }?.delete() }
        list.removeAll { it.meta.name == name }
        saveList(context, list)

        val wasActive = activeName(context) == name
        if (wasActive) {
            val target = adrenotoolsDir(context)
            if (target.exists()) target.deleteRecursively()
            setActiveName(context, null)
            // Rewrite drv so it flips back to the system block, keeping graphics.
            writeDrvFile(context, prefs)
        }
        Log.i(TAG, "Uninstalled $name (wasActive=$wasActive)")
        return true
    }

    // ─────────── Activate custom driver ───────────

    /**
     * Wipes usr/opt/adrenotools, extracts the ZIP, re-reads meta.json from the
     * extracted folder, persists the active name, and rewrites usr/opt/drv with
     * the merged (driver + graphics) content.
     */
    suspend fun activate(
        context: Context,
        prefs: SharedPreferences,
        name: String,
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val entry = refreshAndList(context).firstOrNull { it.meta.name == name }
                ?: return@withContext Result.failure(Exception("Driver $name not installed"))
            val zip = File(entry.zipPath)
            if (!zip.isFile)
                return@withContext Result.failure(Exception("ZIP missing: ${entry.zipPath}"))

            // 1. Clean the target folder (critical: adrenotools loads from here)
            val target = adrenotoolsDir(context)
            onProgress(10, "Cleaning adrenotools folder…")
            if (target.exists()) target.deleteRecursively()
            target.mkdirs()

            // 2. Extract
            onProgress(35, "Extracting $name…")
            unzip(zip, target)

            // 3. Re-read meta.json from the extracted folder (authoritative)
            val metaFile = File(target, "meta.json")
            if (!metaFile.isFile)
                return@withContext Result.failure(Exception("meta.json missing after extract"))
            val meta = parseMeta(metaFile.readText())
            if (meta.name.isBlank() || meta.libraryName.isBlank())
                return@withContext Result.failure(Exception("meta.json missing name/libraryName"))

            // 4. Persist selection + write merged drv (adreno block + graphics block)
            setActiveName(context, meta.name)
            onProgress(80, "Writing usr/opt/drv…")
            writeDrvFile(context, prefs)

            onProgress(100, "Activated ${meta.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "activate failed", e)
            Result.failure(e)
        }
    }

    // ─────────── Activate system driver ───────────

    /**
     * Wipes usr/opt/adrenotools, clears the active name, and rewrites
     * usr/opt/drv with the system block followed by the graphics block.
     */
    fun activateSystem(context: Context, prefs: SharedPreferences): Boolean = try {
        val target = adrenotoolsDir(context)
        if (target.exists()) target.deleteRecursively()
        setActiveName(context, null)
        writeDrvFile(context, prefs)
        Log.i(TAG, "System driver activated")
        true
    } catch (e: Exception) {
        Log.e(TAG, "activateSystem failed", e)
        false
    }

    // ─────────── The single writer ───────────

    /**
     * Composes and writes usr/opt/drv:
     *   [adreno block]              ← from active driver (or unset for system)
     *   <blank line>
     *   [graphics / OpenGL block]   ← from DisplayOrchestrator.buildNativeGraphicsEnv()
     *
     * This is the ONLY method that writes the drv file.
     */
    fun writeDrvFile(context: Context, prefs: SharedPreferences) {
        val adrenoBlock = buildAdrenoEnvBlock(context)
        val graphicsBlock = DisplayOrchestrator.buildNativeGraphicsEnv(context, prefs)

        val merged = buildString {
            append(adrenoBlock.trimEnd())
            append("\n\n")
            append(graphicsBlock.trimEnd())
            append("\n")
        }

        try {
            val f = drvFile(context)
            f.parentFile?.mkdirs()
            f.writeText(merged)
            f.setReadable(true, false)
            f.setWritable(true, false)
            f.setExecutable(false)
            Log.i(TAG, "Wrote merged drv file (adreno + graphics) -> ${f.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write ${drvFile(context).absolutePath}", e)
        }
    }

    // ─────────── Adreno block builders ───────────

    private fun buildAdrenoEnvBlock(context: Context): String {
        val active = activeName(context)
        if (active.isNullOrBlank()) return buildSystemBlock()

        val entry = refreshAndList(context).firstOrNull { it.meta.name == active }
        return if (entry != null) buildDriverBlock(entry.meta) else buildSystemBlock()
    }

    private fun buildDriverBlock(meta: DriverMeta): String = buildString {
        appendLine("# ── Adrenotools: custom driver (${meta.name}) ──")
        appendLine("export ADRENOTOOLS_DRIVER_PATH=\"$PREFIX_HOST/opt/adrenotools/\"")
        appendLine("export ADRENOTOOLS_DRIVER_NAME=\"${meta.libraryName}\"")
        appendLine("export ADRENOTOOLS_HOOKS_PATH=\"$PREFIX_HOST/lib/\"")
        appendLine("export VK_ICD_FILENAMES=$PREFIX_HOST/share/vulkan/icd.d/wrapper_icd.aarch64.json")
        appendLine("export VK_DRIVER_FILES=$PREFIX_HOST/share/vulkan/icd.d/wrapper_icd.aarch64.json")
        appendLine("export MESA_VK_WSI_PRESENT_MODE=mailbox")
        appendLine("export TU_DEBUG=noconform")
    }

    private fun buildSystemBlock(): String = buildString {
        appendLine("# ── Adrenotools: system driver ──")
        appendLine("unset ADRENOTOOLS_DRIVER_PATH")
        appendLine("unset ADRENOTOOLS_DRIVER_NAME")
        appendLine("unset ADRENOTOOLS_HOOKS_PATH")
        appendLine("export VK_ICD_FILENAMES=$PREFIX_HOST/share/vulkan/icd.d/wrapper_icd.aarch64.json")
        appendLine("export VK_DRIVER_FILES=$PREFIX_HOST/share/vulkan/icd.d/wrapper_icd.aarch64.json")
        appendLine("export MESA_VK_WSI_PRESENT_MODE=mailbox")
        appendLine("export TU_DEBUG=noconform")
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