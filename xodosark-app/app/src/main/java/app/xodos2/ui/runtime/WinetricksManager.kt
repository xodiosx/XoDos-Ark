package app.xodos2.ui.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * - Ensures usr/bin/winetricks is installed (copied from usr/opt/winetricks[.sh]).
 * - Persists the verb catalogue in usr/opt/winetricks_verbs.json (seeded from
 *   WinetricksVerbData.ALL on first launch, updated on demand by updateVerbs()).
 */
object WinetricksManager {

    private const val TAG = "WinetricksManager"
    private const val VERBS_FILE = "winetricks_verbs.json"
    private const val JSON_VERSION = 1

    // ── Paths ────────────────────────────────────────────────────

    fun winetricksPath(context: Context): File =
        File(context.filesDir, "usr/bin/winetricks")

    fun verbsFilePath(context: Context): File =
        File(context.filesDir, "usr/opt/$VERBS_FILE")

    private fun sourceCandidates(context: Context): List<File> {
        val opt = File(context.filesDir, "usr/opt")
        return listOf(File(opt, "winetricks"), File(opt, "winetricks.sh"))
    }

    fun sourceWinetricksPath(context: Context): File? =
        sourceCandidates(context).firstOrNull { it.isFile }

    fun isAvailable(context: Context): Boolean =
        winetricksPath(context).isFile || sourceWinetricksPath(context) != null

    // ── Install winetricks script ───────────────────────────────

    suspend fun ensureInstalled(context: Context): Boolean =
        withContext(Dispatchers.IO) { ensureInstalledBlocking(context) }

    private fun ensureInstalledBlocking(context: Context): Boolean {
        val dest = winetricksPath(context)
        if (dest.isFile && dest.canExecute()) return true

        val source = sourceWinetricksPath(context)
        if (source == null) {
            Log.w(TAG, "No winetricks source in usr/opt")
            return dest.isFile
        }

        return try {
            dest.parentFile?.mkdirs()
            source.copyTo(dest, overwrite = true)
            dest.setReadable(true, false)
            dest.setWritable(true, false)
            dest.setExecutable(true, false)
            Log.i(TAG, "Installed ${source.absolutePath} -> ${dest.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install winetricks: ${e.message}", e)
            false
        }
    }

    // ── Verb list persistence ───────────────────────────────────

    /**
     * Reads verbs from JSON. Seeds the file from WinetricksVerbData.ALL if
     * missing or corrupt (with updatedAt=0 so UI shows "Built-in list").
     */
    fun loadVerbs(context: Context): List<WinetricksVerb> {
        val file = verbsFilePath(context)
        if (file.isFile) {
            loadVerbsJson(file)?.let { if (it.isNotEmpty()) return it }
        }
        saveVerbsJson(file, WinetricksVerbData.ALL, updatedAt = 0L)
        return WinetricksVerbData.ALL
    }

    /** UNIX epoch ms of the last successful update, or 0 when on built-in list. */
    fun lastUpdated(context: Context): Long {
        val file = verbsFilePath(context)
        if (!file.isFile) return 0L
        return try {
            JSONObject(file.readText()).optLong("updatedAt", 0L)
        } catch (_: Exception) {
            0L
        }
    }

    private fun loadVerbsJson(file: File): List<WinetricksVerb>? = try {
        val arr = JSONObject(file.readText()).optJSONArray("verbs") ?: return null
        buildList {
            for (i in 0 until arr.length()) {
                val v = arr.getJSONObject(i)
                val name = v.optString("name", "")
                if (name.isNotEmpty()) {
                    add(
                        WinetricksVerb(
                            name = name,
                            description = v.optString("description", ""),
                            category = v.optString("category", "other"),
                        )
                    )
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read verbs JSON: ${e.message}")
        null
    }

    private fun saveVerbsJson(
        file: File,
        verbs: List<WinetricksVerb>,
        updatedAt: Long = System.currentTimeMillis()
    ): Boolean = try {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        val obj = JSONObject().apply {
            put("version", JSON_VERSION)
            put("updatedAt", updatedAt)
            put("verbs", JSONArray().apply {
                verbs.forEach { v ->
                    put(JSONObject().apply {
                        put("name", v.name)
                        put("description", v.description)
                        put("category", v.category)
                    })
                }
            })
        }
        tmp.writeText(obj.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to write verbs JSON: ${e.message}", e)
        false
    }

    // ── Live update ─────────────────────────────────────────────

    /**
     * Runs `winetricks list-all`, parses, saves JSON. Returns new list on
     * success, Result.failure on any error (existing JSON is left intact).
     */
    suspend fun updateVerbs(context: Context): Result<List<WinetricksVerb>> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureInstalledBlocking(context)) {
                    return@withContext Result.failure(Exception("winetricks binary not found"))
                }
                val output = runListAll(context)
                    ?: return@withContext Result.failure(Exception("winetricks list-all failed"))
                val verbs = parseListAll(output)
                if (verbs.isEmpty()) {
                    return@withContext Result.failure(Exception("no verbs parsed from list-all"))
                }
                if (!saveVerbsJson(verbsFilePath(context), verbs)) {
                    return@withContext Result.failure(Exception("failed to save verb list"))
                }
                Log.i(TAG, "Updated verbs: ${verbs.size}")
                Result.success(verbs)
            } catch (e: Exception) {
                Log.e(TAG, "Update failed", e)
                Result.failure(e)
            }
        }

    private fun runListAll(context: Context): String? {
        val wt = winetricksPath(context)
        if (!wt.isFile) return null
        try { wt.setExecutable(true) } catch (_: Exception) {}

        val usrBin = File(context.filesDir, "usr/bin")
        val usrLib = File(context.filesDir, "usr/lib")
        val env = mapOf(
            "PATH"                            to "${usrBin.absolutePath}:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH"                 to "${usrLib.absolutePath}:${context.applicationInfo.nativeLibraryDir}",
            "TMPDIR"                          to context.cacheDir.absolutePath,
            "HOME"                            to File(context.filesDir, "home").absolutePath,
            "WINEPREFIX"                      to File(context.filesDir, "home/.wine").absolutePath,
            "TERM"                            to "dumb",
            "WINETRICKS_GUI"                  to "none",
            "WINETRICKS_LATEST_VERSION_CHECK" to "disabled",
        )

        return try {
            val cmd = listOf("/system/bin/sh", "-c", "\"${wt.absolutePath}\" list-all 2>/dev/null")
            val proc = ProcessBuilder(cmd)
                .directory(context.cacheDir)
                .redirectErrorStream(false)
                .apply { environment().putAll(env) }
                .start()
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            val exit = proc.waitFor()
            if (exit != 0) {
                Log.w(TAG, "winetricks list-all exit=$exit")
                null
            } else output
        } catch (e: Exception) {
            Log.w(TAG, "list-all failed: ${e.message}")
            null
        }
    }

    private fun parseListAll(output: String): List<WinetricksVerb> {
        val result = mutableListOf<WinetricksVerb>()
        var category = ""
        val lineRe = Regex("^([a-zA-Z0-9_\\-.=]+)\\s+(.*)$")
        for (raw in output.lineSequence()) {
            val line = raw.trimEnd()
            if (line.startsWith("=====")) {
                category = line.trim('=', ' ').trim()
                continue
            }
            if (line.isBlank() || category.isEmpty() || category == "prefix") continue
            val m = lineRe.find(line) ?: continue
            result.add(
                WinetricksVerb(
                    name = m.groupValues[1],
                    description = m.groupValues[2].trim(),
                    category = category
                )
            )
        }
        return result
    }
}