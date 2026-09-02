package app.xodos2.ui.runtime

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.util.Log
import app.xodos2.NativeBridge
import app.xodos2.ProgressCallback
import app.xodos2.PulseAssets
import app.xodos2.VirglAssets
import app.xodos2.ui.prefs.AppPrefs
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.Comparator
import java.util.regex.Pattern
import kotlin.concurrent.thread
import kotlinx.coroutines.TimeoutCancellationException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object NativeInstallCoordinator {

    // ── DISTRO SOURCES ENUM ─────────────────────────────────
    enum class DistroSource {
        EASYCLI, KALI, CUSTOM
    }

    data class DistroDescriptor(
        val distroName: String,
        val distroType: String,
        val archiveName: String,
        val downloadUrl: String,
        val version: String,
        val size: String = "?",             
        val extractDirName: String = ""     
    )

    // Cached distros mapped by their source to allow independent, selective fetching
    private var cachedDistros = mutableMapOf<DistroSource, List<DistroDescriptor>>()

private val ARCH_PACMAN_CONF = """
#
# /etc/pacman.conf
#
# See the pacman.conf(5) manpage for option and repository directives

#
# GENERAL OPTIONS
#
[options]
# The following paths are commented out with their default values listed.
# If you wish to use different paths, uncomment and update the paths.
#RootDir     = /
#DBPath      = /var/lib/pacman/
#CacheDir    = /var/cache/pacman/pkg/
#LogFile     = /var/log/pacman.log
#GPGDir      = /etc/pacman.d/gnupg/
#HookDir     = /etc/pacman.d/hooks/
HoldPkg     = pacman glibc
#XferCommand = /usr/bin/curl -L -C - -f -o %o %u
#XferCommand = /usr/bin/wget --passive-ftp -c -O %o %u
#CleanMethod = KeepInstalled
Architecture = aarch64

# Pacman won't upgrade packages listed in IgnorePkg and members of IgnoreGroup
#IgnorePkg   =
#IgnoreGroup =

#NoUpgrade   =
#NoExtract   =

# Misc options
#UseSyslog
#Color
#NoProgressBar
CheckSpace
#VerbosePkgLists
ParallelDownloads = 5
DownloadUser = alpm
DisableSandbox

# By default, pacman accepts packages signed by keys that its local keyring
# trusts (see pacman-key and its man page), as well as unsigned packages.
SigLevel    = Required DatabaseOptional
LocalFileSigLevel = Optional
#RemoteFileSigLevel = Required

# NOTE: You must run `pacman-key --init` before first using pacman; the local
# keyring can then be populated with the keys of all official Arch Linux ARM
# packagers with `pacman-key --populate archlinuxarm`.

#
# REPOSITORIES
#   - can be defined here or included from another file
#   - pacman will search repositories in the order defined here
#   - local/custom mirrors can be added here or in separate files
#   - repositories listed first will take precedence when packages
#     have identical names, regardless of version number
#   - URLs will have ${'$'}repo replaced by the name of the current repo
#   - URLs will have ${'$'}arch replaced by the name of the architecture
#
# Repository entries are of the format:
#       [repo-name]
#       Server = ServerName
#       Include = IncludePath
#
# The header [repo-name] is crucial - it must be present and
# uncommented to enable the repo.
#

# The testing repositories are disabled by default. To enable, uncomment the
# repo name header and Include lines. You can add preferred servers immediately
# after the header, and they will be used before the default mirrors.

[core]
Include = /etc/pacman.d/mirrorlist

[extra]
Include = /etc/pacman.d/mirrorlist

[alarm]
Include = /etc/pacman.d/mirrorlist

[aur]
Include = /etc/pacman.d/mirrorlist

# An example of a custom package repository.  See the pacman manpage for
# tips on creating your own repositories.
#[custom]
#SigLevel = Optional TrustAll
#Server = file:///home/custompkgs
""".trimIndent()

    private suspend fun getFileSizeFromUrl(urlString: String): String = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 1500 // 1.5-second timeout so a dead link doesn't hang the app
            connection.readTimeout = 1600
            connection.connect()

            val length = connection.contentLengthLong
            connection.disconnect()

            if (length > 0) formatBytes(length) else "?"
        } catch (e: Exception) {
            "?"
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

/**
 * Fetch metadata for a direct URL pointing to a rootfs tarball.
 * Used by the custom URL installer dialog.
 */
suspend fun fetchDistroInfoFromUrl(url: String): DistroDescriptor = withContext(Dispatchers.IO) {
    val archiveName = url.substringAfterLast('/')
    val distroType = guessDistroType(archiveName)
    val distroName = archiveName
        .split(Regex("[-_.]"))
        .firstOrNull()
        ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        ?: "Custom URL"
    val version = extractVersion(archiveName)
    val size = getFileSizeFromUrl(url)   // already private, but called from within the object

    DistroDescriptor(
        distroName = "$distroName (URL)",
        distroType = distroType,
        archiveName = archiveName,
        downloadUrl = url,
        version = version,
        size = size,
        extractDirName = ""
    )
}
    /**
     * Fetches available distributions based on the selected source.
     * @param source The platform to scrape or load from.
     * @param customUrls A list of direct tarball URLs. Only used when source is CUSTOM.
     */
    suspend fun fetchAvailableDistros(
        source: DistroSource,
        customUrls: List<String> = emptyList()
    ): List<DistroDescriptor> {
        
        // Custom URLs are dynamic, so we bypass the static cache for them
        if (source == DistroSource.CUSTOM) {
            return withContext(Dispatchers.IO) {
                // Fallback to Alpine if no custom URLs are provided
               val urlsToFetch = customUrls.ifEmpty {
    listOf(
        // Others 
        "https://github.com/xodiosx/XoDos-Ark/releases/download/v2.34.7/nixos-aarch64-pd-v2.34.7.tar.xz",
      
        // All aarch64 distros from XoDos-Ark mirror-v4.17.3
        "https://github.com/xodiosx/XoDos-Ark/releases/download/mirror-v4.17.3/alpine-aarch64-pd-v4.17.3.tar.xz",
        "https://github.com/termux/proot-distro/releases/download/v4.18.0/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz",
        "https://github.com/xodiosx/XoDos-Ark/releases/download/mirror-v4.17.3/chimera-aarch64-pd-v4.17.3.tar.xz",
        "https://github.com/xodiosx/XoDos-Ark/releases/download/mirror-v4.17.3/debian-bookworm-aarch64-pd-v4.17.3.tar.xz",
        "https://github.com/xodiosx/XoDos-Ark/releases/download/mirror-v4.17.3/fedora-aarch64-pd-v4.17.3.tar.xz",
        "https://github.com/xodiosx/XoDos-Ark/releases/download/mirror-v4.17.3/archlinux-aarch64-pd-v4.17.3.tar.xz",
        "https://github.com/xodiosx/XoDos-Ark/releases/download/mirror-arm64-rf/kali-rootfs-arm64.tar.xz"
    )
}

                urlsToFetch.map { url ->
                    async {
                        val archiveName = url.substringAfterLast('/')
                        val distroType = guessDistroType(archiveName)
                        val distroName = archiveName.split(Regex("[-_.]")).firstOrNull()?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } ?: "Custom"
                        val version = extractVersion(archiveName)
                        val realSize = getFileSizeFromUrl(url)

                        DistroDescriptor(
                            distroName = "$distroName (Custom)",
                            distroType = distroType,
                            archiveName = archiveName,
                            downloadUrl = url,
                            version = version,
                            size = realSize,
                            extractDirName = ""
                        )
                    }
                }.awaitAll()
            }
        }

        // Use cache for KALI and EASYCLI
        return cachedDistros[source] ?: withContext(Dispatchers.IO) {
            val all = mutableListOf<DistroDescriptor>()

            when (source) {
                DistroSource.KALI -> {
                    // =======================================================
                    // 1. SCRAPE KALI NETHUNTER ROOTFS
                    // =======================================================
                    val kaliUrl = "https://kali.download/nethunter-images/current/rootfs/"
                    try {
                        withTimeout(15_000L) {
                            val doc = org.jsoup.Jsoup.connect(kaliUrl).timeout(10_000).get()
                            val links = doc.select("a[href]")

                            coroutineScope {
                                val kaliDeferred = links.filter { link ->
                                    val href = link.attr("abs:href")
                                    href.contains("arm64", ignoreCase = true) && 
                                    (href.endsWith(".tar.xz", ignoreCase = true) || href.endsWith(".tar.gz", ignoreCase = true))
                                }.map { link ->
                                    async {
                                        val fullUrl = link.attr("abs:href")
                                        val archiveName = fullUrl.substringAfterLast('/')
                                        val version = extractVersion(archiveName)
                                        val realSize = getFileSizeFromUrl(fullUrl)

                                        DistroDescriptor(
                                            distroName = "Kali Nethunter",
                                            distroType = "kali",
                                            archiveName = archiveName,
                                            downloadUrl = fullUrl,
                                            version = version,
                                            size = realSize,
                                            extractDirName = ""
                                        )
                                    }
                                }
                                all.addAll(kaliDeferred.awaitAll())
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("NativeInstall", "Failed to fetch Kali distros", e)
                    }
                }

                                DistroSource.EASYCLI -> {
                    // =======================================================
                    // 2. SCRAPE LXC INDEX (Alternative to EasyCLI)
                    // =======================================================
                    val lxcIndexUrl = "https://images.linuxcontainers.org/meta/1.0/index-system"
                    try {
                        withTimeout(20_000L) {
                            val url = URL(lxcIndexUrl)
                            val connection = url.openConnection() as HttpURLConnection
                            connection.requestMethod = "GET"
                            connection.connectTimeout = 10_000
                            connection.readTimeout = 10_000

                            // The index file is plain text, semicolon-delimited
                            val response = connection.inputStream.bufferedReader().use { it.readText() }
                            
                            coroutineScope {
                                val lxcDeferred = response.lines()
                                    // Filter for arm64 architecture and default variant
                                    .filter { it.contains(";arm64;default;") }
                                    .map { line ->
                                        async {
                                            // Format: distro;release;arch;variant;date;/path
                                            val parts = line.split(';')
                                            if (parts.size >= 5) {
                                                val lxcDistro = parts[0]
                                                val lxcRelease = parts[1]
                                                val arch = parts[2]
                                                val variant = parts[3]
                                                val date = parts[4]

                                                // The LXC server requires the colon in the timestamp to be URL-encoded
                                                val encodedDate = date.replace(":", "%3A")
                                                
                                                // Construct the direct tarball URL
                                                val fullUrl = "https://images.linuxcontainers.org/images/$lxcDistro/$lxcRelease/$arch/$variant/$encodedDate/rootfs.tar.xz"
                                                
                                                // Create a unique local name so cache files don't clash
                                                val archiveName = "${lxcDistro}_${lxcRelease}_${arch}_rootfs.tar.xz"
                                                
                                                val distroName = "${lxcDistro.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }} $lxcRelease"
                                                val distroType = guessDistroType(lxcDistro)
                                                
                                                // Fetch file size (can take a moment for many distros, consider skipping if UI is too slow)
                                                val realSize = getFileSizeFromUrl(fullUrl)

                                                DistroDescriptor(
                                                    distroName = distroName,
                                                    distroType = distroType,
                                                    archiveName = archiveName,
                                                    downloadUrl = fullUrl,
                                                    version = lxcRelease,
                                                    size = realSize,
                                                    extractDirName = ""
                                                )
                                            } else {
                                                null
                                            }
                                        }
                                    }
                                all.addAll(lxcDeferred.awaitAll().filterNotNull())
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("NativeInstall", "Failed to fetch LXC distros", e)
                    }
                }

                else -> {}
            }

            // =======================================================
            // SORT AND CACHE RESULTS
            // =======================================================
            val sorted = all.sortedWith(
                Comparator { a, b ->
                    val v = compareVersions(b.version, a.version)
                    if (v != 0) v else a.distroName.compareTo(b.distroName)
                }
            )
            cachedDistros[source] = sorted
            sorted
        }
    }

    fun invalidateDistroCache() {
        cachedDistros.clear()
    }

    // ---------- container helpers ----------
    fun containerPath(context: Context, containerId: Int): File =
        File(context.filesDir, "containers/$containerId")

    fun containerIsOccupied(context: Context, containerId: Int): Boolean {
        val dir = containerPath(context, containerId)
        return dir.isDirectory && dir.list()?.isNotEmpty() == true
    }

    suspend fun deleteContainerContents(context: Context, containerId: Int): Boolean =
    withContext(Dispatchers.IO) {
        val dir = containerPath(context, containerId)
        if (!dir.isDirectory) {
            Log.w("NativeInstall", "deleteContainer: not a directory $dir")
            return@withContext false
        }

        try {
            // First make everything owner-writable/executable so rm -rf can delete it.
            val pb = ProcessBuilder(
                "/system/bin/sh",
                "-c",
                "chmod -R 755 \"\$1\" && rm -rf \"\$1\"",
                "_",
                dir.absolutePath
            ).redirectErrorStream(true)

            val process = pb.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                Log.i("NativeInstall", "Container $containerId cleared via shell chmod + rm -rf")
                dir.mkdirs()  // recreate empty container directory
                true
            } else {
                Log.e("NativeInstall", "Shell chmod/rm -rf failed with exit $exitCode. Output: $output")
                false
            }
        } catch (e: Exception) {
            Log.e("NativeInstall", "Failed to delete container $containerId via shell", e)
            false
        }
    }
    
    private fun configureDns(context: Context, containerId: Int) {
    try {
        val rootfsPath = containerPath(context, containerId)
        val resolvConf = File(rootfsPath, "etc/resolv.conf")

        // Remove existing symlink or broken file first
        if (resolvConf.exists() || android.system.Os.lstat(resolvConf.absolutePath) != null) {
            val isSymlink = try {
                android.system.Os.lstat(resolvConf.absolutePath).st_mode and android.system.OsConstants.S_IFMT == android.system.OsConstants.S_IFLNK
            } catch (_: Exception) { false }
            if (isSymlink) {
                resolvConf.delete()
            }
        }

        resolvConf.parentFile?.mkdirs()
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        Log.i("NativeInstall", "DNS configured for container $containerId")
    } catch (e: Exception) {
        Log.e("NativeInstall", "Failed to write resolv.conf", e)
    }
}

  fun writeContainerEnvironment(context: Context, containerId: Int, distroId: String) {
    val rootfs = containerPath(context, containerId)
    val bashrc = File(rootfs, "etc/bash.bashrc")
    bashrc.parentFile?.mkdirs()
    val distFile = File(rootfs, ".rootfs_type")

    val envLines = """

        # XoDos-ark environment
        unset GALLIUM_DRIVER MESA_DRIVER_PATH MESA_LOADER_DRIVER_OVERRIDE TU_DEBUG VK_ICD_FILENAMES MESA_VK_WSI_PRESENT_MODE MESA_LOADER_DRIVER_OVERRIDE VKD3D_FEATURE_LEVEL VK_DRIVER_FILES VN_DEBUG || true
        export WAYLAND_DISPLAY=wayland-xodos2
        if [ -f /.x11 ]; then
         export DISPLAY=:0
         unset WAYLAND_DISPLAY
        fi
        export PULSE_SERVER=127.0.0.1        
        export MOZ_FAKE_NO_SANDBOX=1
        export DISTRO=$distroId
        source /etc/environment
    """.trimIndent()

    val stype = """
        |$distroId
    """.trimMargin()

    distFile.writeText(stype)

    // Only append the environment block if it's not already present
    val existing = if (bashrc.exists()) bashrc.readText() else ""
    if (!existing.contains("# XoDos-ark environment")) {
        try {
            if (bashrc.exists()) {
                bashrc.appendText(envLines)
            } else {
                bashrc.writeText(envLines.trimStart())
            }
            Log.i("NativeInstall", "bash.bashrc updated with environment for container $containerId")
        } catch (e: Exception) {
            Log.e("NativeInstall", "Failed to write to bash.bashrc", e)
        }
    } else {
        Log.d("NativeInstall", "bash.bashrc already contains XoDos-ark environment, skipping")
    }
}

    private const val PREF_CONTAINER_DISTRO = "container_distro_"

    fun saveContainerDistro(context: Context, containerId: Int, distroId: String) {
        context.getSharedPreferences("xodos2_containers", Context.MODE_PRIVATE)
            .edit()
            .putString("$PREF_CONTAINER_DISTRO$containerId", distroId)
            .apply()
    }

    fun getContainerDistro(context: Context, containerId: Int): String? {
        return context.getSharedPreferences("xodos2_containers", Context.MODE_PRIVATE)
            .getString("$PREF_CONTAINER_DISTRO$containerId", null)
    }

    fun getContainerDisplayName(context: Context, containerId: Int): String {
        val distro = getContainerDistro(context, containerId)
        return if (distro != null) {
            distro.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        } else {
            "Container $containerId"
        }
    }

    fun detectDistroFromRootfs(context: Context, containerId: Int): String? {
        val rootfs = containerPath(context, containerId)

        val etcRelease = File(rootfs, "etc/os-release")
        if (etcRelease.exists() && etcRelease.isFile) {
            return parseOsRelease(etcRelease.readText())
        }

        val usrLibRelease = File(rootfs, "usr/lib/os-release")
        if (usrLibRelease.exists() && usrLibRelease.isFile) {
            return parseOsRelease(usrLibRelease.readText())
        }

        if (File(rootfs, "etc/debian_version").exists()) return "debian"
        if (File(rootfs, "etc/arch-release").exists())  return "archlinux"
        if (File(rootfs, "etc/alpine-release").exists()) return "alpine"
        if (File(rootfs, "etc/void-release").exists())   return "void"
        if (File(rootfs, "etc/fedora-release").exists()) return "fedora"
            // NixOS and Guix have store directories
       if (File(rootfs, "nix/store").isDirectory) return "nixos"
       if (File(rootfs, "gnu/store").isDirectory) return "guix"
        return null
    }

    private fun parseOsRelease(content: String): String? {
        val id = content.lines()
            .firstOrNull { it.startsWith("ID=") }
            ?.substringAfter("ID=")
            ?.replace("\"", "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        return when (id) {
            "archarm" -> "archlinux"
            else -> id
        }
    }

    suspend fun backupContainerToUri(
        context: Context,
        containerId: Int,
        destUri: Uri,
        onProgress: (pct: Int, msg: String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val sourceDir = containerPath(context, containerId)
        if (!sourceDir.isDirectory) {
            Log.w("NativeInstall", "backup: container $containerId is not a directory")
            return@withContext false
        }

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val prootExe = File(nativeLibDir, "libproot.so")
        val prootLoader = File(nativeLibDir, "libproot_loader.so")

        if (!prootExe.exists() || !prootLoader.exists()) {
            Log.e("NativeInstall", "proot binary or loader not found in $nativeLibDir")
            return@withContext false
        }

        val prootEnv = mutableMapOf<String, String>()
        prootEnv["PROOT_LOADER"] = prootLoader.absolutePath
        prootEnv["PROOT_TMP_DIR"] = context.cacheDir.absolutePath
        prootEnv["HOME"] = "/root"
        prootEnv["PATH"]  = "/data/data/app.xodos2/files/usr/bin:/system/bin:/sbin:/bin"
        prootEnv["LD_LIBRARY_PATH"] = "/data/data/app.xodos2/files/usr/lib:/system/lib64/:/lib"
        prootEnv["TMPDIR"] = "/tmp"

        val tarCmd = "busybox tar " +
            "--exclude='${sourceDir.name}/system' " +
            "--exclude='${sourceDir.name}/apex' " +
            "--exclude='${sourceDir.name}/data' " +
            "--exclude='${sourceDir.name}/sdcard' " +
            "--exclude='${sourceDir.name}/storage' " +
            "-Jcp -C ${sourceDir.parent} ${sourceDir.name}"
            
        val cmd = mutableListOf(
            prootExe.absolutePath,
            "--change-id=0:0",
            "--pwd=/",
            "--link2symlink",
            "--kill-on-exit",
            "--sysvipc",
            "--bind=/system",
            "--bind=/apex",
            "--bind=/data",
            "--bind=/sdcard",
            "sh", "-c",
            tarCmd
        )

        try {
            onProgress(0, "Starting backup...")

            val pb = ProcessBuilder(cmd)
                .directory(context.cacheDir)
                .apply { environment().putAll(prootEnv) }

            val process = pb.start()

            val errorText = StringBuilder()
            val errorThread = thread {
                process.errorStream.bufferedReader().use { errorText.append(it.readText()) }
            }

            context.contentResolver.openOutputStream(destUri)?.use { outputStream ->
                process.inputStream.use { inputStream ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var totalBytesWritten = 0L
                    
                    val startTime = System.currentTimeMillis()
                    val estimatedDurationMs = 5 * 60 * 1000L
                    var lastReportTime = startTime

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesWritten += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastReportTime > 200) {
                            val elapsedMs = now - startTime
                            val timeProgress = ((elapsedMs.toDouble() / estimatedDurationMs) * 100).toInt()
                            val safePct = timeProgress.coerceIn(1, 95)
                            val mbWritten = totalBytesWritten / (1024 * 1024)
                            
                            onProgress(safePct, "Compressing: $mbWritten MB written")
                            lastReportTime = now
                        }
                    }
                }
            } ?: throw Exception("Failed to open backup destination stream")

            val exitCode = process.waitFor()
            errorThread.join()

            if (exitCode != 0) {
                Log.e("NativeInstall", "tar failed with exit $exitCode: $errorText")
                onProgress(-1, "Backup failed")
                return@withContext false
            }

            onProgress(100, "Backup saved")
            true
        } catch (e: Exception) {
            Log.e("NativeInstall", "Backup failed", e)
            onProgress(-1, "Backup failed: ${e.message}")
            false
        }
    }

    suspend fun cleanCacheTarballs(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            val cacheDir = context.cacheDir
            var allOk = true
            cacheDir.listFiles { f -> 
                f.name.endsWith(".tar.xz") || f.name.endsWith(".tar.gz") 
            }?.forEach {
                if (!it.delete()) {
                    allOk = false
                    Log.e("NativeInstall", "Failed to delete ${it.name}")
                }
            }
            if (allOk) Log.i("NativeInstall", "Cache tarballs cleaned")
            else Log.e("NativeInstall", "Some cache tarballs could not be deleted")
            allOk
        }

    // ---------- installation ----------
    suspend fun installDistroToContainer(
        context: Context,
        distro: DistroDescriptor,
        containerId: Int,
        onProgress: (pct: Int, msg: String) -> Unit,
        onConfirmOverwrite: suspend () -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val occupied = containerIsOccupied(context, containerId)
        if (occupied) {
            val confirmed = onConfirmOverwrite()
            if (!confirmed) return@withContext false
            deleteContainerContents(context, containerId)
        }

        val extractPath = containerPath(context, containerId)
        extractPath.mkdirs()
        val ok = NativeBridge.installToContainer(
            containerId = containerId,
            url = distro.downloadUrl,
            tarballName = distro.archiveName,
            callback = object : ProgressCallback {
                override fun onProgress(pct: Int, msg: String) = onProgress(pct, msg)
            }
        )
        if (ok) {
            configureDns(context, containerId)      
            //copyAssetToContainer(context, containerId, "xfce4-fix.zip")       
            val detected = detectDistroFromRootfs(context, containerId) ?: distro.distroType
            writeContainerEnvironment(context, containerId, detected)
            applyProotBypasses(context, containerId, detected)
            saveContainerDistro(context, containerId, detected)
            applyArchPacmanFixes(context, containerId, detected)
            applyNixOsFixes(context, containerId, detected)    
        }
        ok
    }

    suspend fun extractRootfsFromUriToContainer(
        context: Context,
        uri: Uri,
        containerId: Int,
        onProgress: (pct: Int, msg: String) -> Unit,
        onConfirmOverwrite: suspend () -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        if (containerIsOccupied(context, containerId)) {
            if (!onConfirmOverwrite()) return@withContext false
            deleteContainerContents(context, containerId)
        }

        var fileName = ""
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx != -1) fileName = cursor.getString(idx)
            }
        }
        val ext = if (fileName.lowercase().endsWith(".gz")) ".tar.gz" else ".tar.xz"
        val tempName = "container${containerId}_local$ext"
        val destFile = File(context.cacheDir, tempName)

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }

        onProgress(55, "Extracting...")
        val ok = NativeBridge.installToContainer(
            containerId = containerId,
            url = "",
            tarballName = tempName,
            callback = object : ProgressCallback {
                override fun onProgress(pct: Int, msg: String) = onProgress(55 + (pct / 2), msg)
            }
        )

        if (ok) {
            configureDns(context, containerId)
            //copyAssetToContainer(context, containerId, "xfce4-fix.zip") 
            val detected = detectDistroFromRootfs(context, containerId) ?: "linux"
            writeContainerEnvironment(context, containerId, detected)
            applyProotBypasses(context, containerId, detected)
            saveContainerDistro(context, containerId, detected)
            applyArchPacmanFixes(context, containerId, detected)
            applyNixOsFixes(context, containerId, detected)    
        }
        ok
    }

    // ---------- init + symlink setup ----------
    data class InitResult(
        val ok: Boolean,
        val hasArchRootfs: Boolean,
        val hasDebianRootfs: Boolean,
        val hasWineRootfs: Boolean,
        val desktopModes: GraphicsModeController.Modes,
    )

    suspend fun initNativeAndSyncAssets(
        context: Context,
        prefs: SharedPreferences,
        allowedVulkan: List<String>,
        allowedOpenGL: List<String>,
    ): InitResult {
        migrateRendererPrefsIfNeeded(prefs)
        val desktopModes = GraphicsModeController.loadFromPrefs(
            prefs = prefs,
            allowedVulkan = allowedVulkan,
            allowedOpenGL = allowedOpenGL,
        )
        val ok = withContext(Dispatchers.IO) {
            if (!NativeBridge.init(
                    context.filesDir.absolutePath,
                    context.cacheDir.absolutePath,
                    context.applicationInfo.nativeLibraryDir,
                    Environment.getExternalStorageDirectory()?.absolutePath
                )
            ) return@withContext false

            setupNativeEnvironment(context)

            NativeBridge.stopVirglHost()
            PulseAssets.syncFromAssetsIfNeeded(context)
            VirglAssets.syncFromAssetsIfNeeded(context)
            true
        }
        prefs.edit().remove("display_startup_script").apply()
        if (!ok) {
            return InitResult(
                ok = false,
                hasArchRootfs = false, hasDebianRootfs = false, hasWineRootfs = false,
                desktopModes = desktopModes,
            )
        }
        return InitResult(
            ok = true,
            hasArchRootfs = NativeBridge.hasArchRootfs(),
            hasDebianRootfs = NativeBridge.hasDebianRootfs(),
            hasWineRootfs = NativeBridge.hasWineRootfs(),
            desktopModes = desktopModes,
        )
    }

    private fun setupNativeEnvironment(context: Context) {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val usrBin = File(context.filesDir, "usr/bin").apply { mkdirs() }
        val usrLib = File(context.filesDir, "usr/lib").apply { mkdirs() }

        val libFiles = mapOf(
            "libbusybox.so" to "libbusybox.so.1.37.0",
            "liblzma.so" to "liblzma.so.5",
            "libproot_loader.so" to "libproot_loader.so",
             "libtalloc.so" to "libtalloc.so.2",
             "libandroid-shmem.so" to "libandroid-shmem.so"
        )
        for ((src, dst) in libFiles) {
            val srcFile = File(nativeLibDir, src)
            val dstFile = File(usrLib, dst)
            if (srcFile.exists() && !dstFile.exists()) {
                try {
                    srcFile.inputStream().use { input ->
                        dstFile.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    Log.e("NativeInstall", "Failed to copy $src -> $dst", e)
                }
            }
        }

        val symlinks = mapOf(
            "libexec_busybox.so" to "busybox",
            "libexec_tar.so"    to "tar",
            "libproot.so"       to "proot",
            "libxz.so"          to "xz",
            "libpv.so"          to "pv",
            "libgzip.so"        to "gzip"
        )
        for ((target, linkName) in symlinks) {
            val targetPath = "$nativeLibDir/$target"
            val linkPath = File(usrBin, linkName).absolutePath
            try {
                val pb = ProcessBuilder(
                    "/system/bin/sh", "-c",
                    "ln -sf $targetPath $linkPath && chmod +x $targetPath"
                ).redirectErrorStream(true)
                val p = pb.start()
                p.waitFor()
            } catch (e: Exception) {
                Log.e("NativeInstall", "Failed to create symlink $linkName", e)
            }
        }
    }

    // ---------- private utilities ----------
    private fun guessDistroType(name: String): String {
        val n = name.lowercase()
        return when {
            "archlinux" in n -> "archlinux"
            "debian" in n -> "debian"
            "ubuntu" in n -> "ubuntu"
            "fedora" in n -> "fedora"
            "alpine" in n -> "alpine"
            "opensuse" in n -> "opensuse"
            "kali" in n -> "kali"
            "manjaro" in n -> "manjaro"
            "deepin" in n -> "deepin"
            "alma" in n -> "almalinux"
            "artix" in n -> "artix"
            "rocky" in n -> "rocky"
            "void" in n -> "voidx"
            "trisquel" in n -> "trisquel"
            "nixos" in n -> "nixos"
            else -> "linux"
        }
    }

    private fun extractVersion(name: String): String =
        Pattern.compile("v(\\d+(\\.\\d+)*)").matcher(name).let {
            if (it.find()) it.group() else "0.0"
        }

    private fun compareVersions(a: String, b: String): Int {
        val partsA = a.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val partsB = b.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val numA = partsA.getOrElse(i) { 0 }
            val numB = partsB.getOrElse(i) { 0 }
            if (numA != numB) return numA.compareTo(numB)
        }
        return 0
    }

/**
 * Copies an asset file from the APK into the container's root directory.
 */
private fun copyAssetToContainer(context: Context, containerId: Int, assetName: String) {
    val rootfs = containerPath(context, containerId)
    if (!rootfs.isDirectory) return

    val destFile = File(rootfs, assetName)
    try {
        context.assets.open(assetName).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        Log.i("NativeInstall", "$assetName copied to container $containerId")
    } catch (e: Exception) {
        Log.e("NativeInstall", "Failed to copy $assetName to container $containerId", e)
    }
}


private fun applyNixOsFixes(context: Context, containerId: Int, distroType: String) {
    Log.d("NativeInstall", "applyNixOsFixes called: container=$containerId, distro=$distroType")

    if (!distroType.lowercase().contains("nix")) {
        Log.d("NativeInstall", "Not a NixOS distro, skipping")
        return
    }

    val rootfs = containerPath(context, containerId)
    val homeDir = File(rootfs, "root")
    if (!homeDir.exists()) homeDir.mkdirs()

    val bashrc = File(homeDir, ".bashrc")
    val bashProfile = File(homeDir, ".bash_profile")

    val bashrcContent = "echo ' Welcome to NixOS '\n" +
            "# Override nix-channel to make --update safe\n" +
            "nix-channel() {\n" +
            "export NATIVE_LIB=/data/data/app.xodos2/files/usr\n" +
            "    if [ \"\$1\" = \"--update\" ]; then\n" +
            "        # Use the second argument as channel name, default to nixos-unstable\n" +
            "        CHANNEL=\"\${2:-nixos-24.11}\"\n" +
            "        TARBALL_URL=\"https://channels.nixos.org/\$CHANNEL/nixexprs.tar.xz\"\n" +
            "        TARGET_DIR=\"\$HOME/.nix-defexpr/channels/nixpkgs\"\n" +
            "        TMP_DIR=\$(mktemp -d)\n" +
            "\n" +
            "        echo \"🔄 Updating \$CHANNEL channel safely...\"\n" +
            "        curl -L \"\$TARBALL_URL\" -o \"\$TMP_DIR/nixpkgs.tar.xz\" || {\n" +
            "            echo \"Download failed\"; rm -rf \"\$TMP_DIR\"; return 1\n" +
            "        }\n" +
            "       PATH=\"\$NATIVE_LIB/bin:\$PATH\" LD_LIBRARY_PATH=\"\$NATIVE_LIB/lib:\$LD_LIBRARY_PATH\" tar -xJf \"\$TMP_DIR/nixpkgs.tar.xz\" -C \"\$TMP_DIR\" || {\n" +
            "            echo \"Extraction failed\"; rm -rf \"\$TMP_DIR\"; return 1\n" +
            "        }\n" +
            "        EXTRACTED=\$(find \"\$TMP_DIR\" -maxdepth 1 -type d -name \"nixos-*\" | head -n1)\n" +
            "        if [ -z \"\$EXTRACTED\" ]; then\n" +
            "            echo \"Error: extracted folder not found\"; rm -rf \"\$TMP_DIR\"; return 1\n" +
            "        fi\n" +
            "        rm -rf \"\$TARGET_DIR\"\n" +
            "        mv \"\$EXTRACTED\" \"\$TARGET_DIR\"\n" +
            "        rm -rf \"\$TMP_DIR\"\n" +
            "        echo \"✅ Channel updated to \$CHANNEL\"\n" +
            "        echo \"💡 Ensure NIX_PATH is set: export NIX_PATH=nixpkgs=~/.nix-defexpr/channels/nixpkgs\"\n" +
            "    else\n" +
            "        # Forward all other arguments to the real nix-channel\n" +
            "        command nix-channel \"\$@\"\n" +
            "    fi\n" +
            "}\n" +
            "\n" +
            "# XoDos-ark environment\n" +
            "unset GALLIUM_DRIVER MESA_DRIVER_PATH MESA_LOADER_DRIVER_OVERRIDE TU_DEBUG VK_ICD_FILENAMES MESA_VK_WSI_PRESENT_MODE MESA_LOADER_DRIVER_OVERRIDE VKD3D_FEATURE_LEVEL VK_DRIVER_FILES VN_DEBUG || true\n" +
            "export WAYLAND_DISPLAY=wayland-xodos2\n" +
            "if [ -f /.x11 ]; then\n" +
            " export DISPLAY=:0\n" +
            " unset WAYLAND_DISPLAY\n" +
            "fi\n" +
            "export PULSE_SERVER=127.0.0.1\n" +
            "export MOZ_FAKE_NO_SANDBOX=1\n" +
            "export DISTRO=nixos\n" +
            "source /etc/environment\n"

    val bashProfileContent = "if [ -f ~/.bashrc ]; then\n" +
            "    . ~/.bashrc\n" +
            "fi\n"

    try {
        bashrc.writeText(bashrcContent)
        bashrc.setReadable(true, false)
        bashrc.setWritable(true, false)
        bashrc.setExecutable(false)

        bashProfile.writeText(bashProfileContent)
        bashProfile.setReadable(true, false)
        bashProfile.setWritable(true, false)
        bashProfile.setExecutable(false)

        Log.i("NativeInstall", "Wrote NixOS .bashrc and .bash_profile for container $containerId")
    } catch (e: Exception) {
        Log.e("NativeInstall", "Failed to write NixOS shell config", e)
    }
}

private fun applyArchPacmanFixes(context: Context, containerId: Int, distroType: String) {
    Log.d("NativeInstall", "applyArchPacmanFixes called: container=$containerId, distro=$distroType")

    if (!distroType.lowercase().contains("arch")) {
        Log.d("NativeInstall", "Not an Arch distro, skipping")
        return
    }

    val rootfs = containerPath(context, containerId)
    val nativeLibDir = context.applicationInfo.nativeLibraryDir
    val prootBinary = File(nativeLibDir, "libproot.so")
    if (!prootBinary.exists()) {
        Log.e("NativeInstall", "proot binary not found at ${prootBinary.absolutePath}")
        return
    }

    // 1. Write known-good pacman.conf (only DisableSandbox) with correct permissions
    try {
        val pacmanConf = File(rootfs, "etc/pacman.conf")
        pacmanConf.parentFile?.mkdirs()
        pacmanConf.writeText(ARCH_PACMAN_CONF)
        pacmanConf.setReadable(true, false)   // owner readable
        pacmanConf.setWritable(true, false)  // owner writable
        pacmanConf.setExecutable(false)      // not executable
        Log.i("NativeInstall", "Wrote pacman.conf with permissions for container $containerId")
    } catch (e: Exception) {
        Log.e("NativeInstall", "Failed to write pacman.conf", e)
        return
    }

    // 2. Create one-time startup script for keyring initialization (chmod 755)
    val profileDir = File(rootfs, "etc/profile.d")
    if (!profileDir.exists()) profileDir.mkdirs()
    val startupScript = File(profileDir, "zz-fix-pacman.sh")
    try {
        startupScript.writeText("""
            #!/bin/sh
            # One-time pacman keyring initializer for XoDos-Ark
         

            echo "Initializing pacman keyring (one-time)..."
            pacman-key --init
            pacman-key --populate archlinuxarm 2>/dev/null || pacman-key --populate archlinux

            mv /etc/profile.d/zz-fix-pacman.sh /etc/profile.d/zz-fix-pacman.sh.disabled
        """.trimIndent())

        // Set permissions: -rwxr-xr-x (755)
        startupScript.setReadable(true, false)   // owner read
        startupScript.setWritable(true, false)  // owner write
        startupScript.setExecutable(true, false) // owner execute
        startupScript.setReadable(true, true)    // group read
        startupScript.setExecutable(true, true)  // group execute
        startupScript.setReadable(true, true)    // others read
        startupScript.setExecutable(true, true)  // others execute

        Log.i("NativeInstall", "Wrote startup script with 755 permissions for container $containerId")
    } catch (e: Exception) {
        Log.e("NativeInstall", "Failed to write startup script", e)
        return
    }

    // 3. Run the script immediately via PRoot so keyring is ready now
    File(rootfs, "tmp").mkdirs()
    val fixScript = """
        sh /etc/profile.d/zz-fix-pacman.sh
    """.trimIndent()

    val cmd = listOf(
        prootBinary.absolutePath,
        "--change-id=0:0",
        "--link2symlink",
        "--kill-on-exit",
        "--sysvipc",
        "--rootfs=${rootfs.absolutePath}",
        "--bind=/dev",
        "--bind=/proc",
        "--bind=/sys",
        "--bind=/dev/urandom:/dev/random",
        "--bind=${rootfs.absolutePath}/tmp:/tmp",
        "--cwd=/root",
        "/bin/sh", "-c", fixScript
    )

    try {
        val process = ProcessBuilder(cmd)
            .directory(context.cacheDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            Log.w("NativeInstall", "Pacman keyring init exit $exitCode: $output")
        } else {
            Log.i("NativeInstall", "Pacman keyring initialized successfully for container $containerId")
        }
    } catch (e: Exception) {
        Log.e("NativeInstall", "Failed to run pacman fix", e)
    }
}


    private fun migrateRendererPrefsIfNeeded(prefs: SharedPreferences) {
        val rawLegacy = prefs.getString("desktop_renderer_mode", "") ?: ""
        val (migrated, shouldRemoveLegacy) = AppPrefs.migrateLegacyRendererMode(rawLegacy)
        if (migrated != null) {
            prefs.edit()
                .remove("desktop_renderer_mode")
                .putString("desktop_vulkan_mode", migrated.first)
                .putString("desktop_opengl_mode", migrated.second)
                .apply()
        } else if (shouldRemoveLegacy) {
            prefs.edit().remove("desktop_renderer_mode").apply()
        }
    }
        private fun applyProotBypasses(context: Context, containerId: Int, distroType: String) {
        // These bypasses are specific to Debian-based package managers (apt/dpkg)
        val debianBased = listOf("kali", "debian", "ubuntu", "deepin")
        if (distroType !in debianBased) return

        val rootfs = containerPath(context, containerId)

        try {
            // 1. Fake ischroot to always return 0 (true)
            val usrBin = File(rootfs, "usr/bin")
            usrBin.mkdirs()
            val ischroot = File(usrBin, "ischroot")
            if (ischroot.exists()) ischroot.delete() // Remove the real one if it exists
            
            // Writing a tiny bash script is safer than Os.symlink for PRoot translation
            ischroot.writeText("#!/bin/sh\nexit 0\n")
            ischroot.setExecutable(true, false) // Make it executable for everyone

            // 2. Policy-rc.d shield to block systemd services from starting during apt installs
            val usrSbin = File(rootfs, "usr/sbin")
            usrSbin.mkdirs()
            val policyRc = File(usrSbin, "policy-rc.d")
            
            policyRc.writeText("#!/bin/sh\nexit 101\n")
            policyRc.setExecutable(true, false)

            Log.i("NativeInstall", "Applied PRoot dpkg bypasses for $distroType in container $containerId")
        } catch (e: Exception) {
            Log.e("NativeInstall", "Failed to apply PRoot bypasses", e)
        }
    }
        
}
