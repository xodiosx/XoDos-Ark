package app.xodos2

/** JNI: `libxodos2.so`. Call [init] before other methods. */
object NativeBridge {
    init {
        System.loadLibrary("xodos2")
    }

    // ── Init ────────────────────────────────────────────
    external fun init(
        dataDir: String,
        cacheDir: String,
        nativeLibraryDir: String,
        externalStorageDir: String?
    ): Boolean

    // ── Virgl control ──────────────────────────────────
    external fun stopVirglHost()
    external fun startVirglHostIfPossible()

/** Starts VirGL servers: mask bit0 = Venus, bit1 = Angle.
 *  e.g. 1 = Venus, 2 = Angle, 3 = Both. */
external fun startVirglServers(mask: Int)

    // ── Old Arch/Debian/Wine check (mapped to containers) ──
    fun hasArchRootfs(): Boolean = hasContainerRootfs(1)
    fun hasDebianRootfs(): Boolean = hasContainerRootfs(2)
    fun hasWineRootfs(): Boolean = hasContainerRootfs(3)

    // ── Old download functions (deprecated) ──────────────
    @Deprecated("Use installToContainer(1, url, name, callback)")
    fun downloadArchRootfs(callback: ProgressCallback): Boolean = false

    @Deprecated("Use installToContainer(2, url, name, callback)")
    fun downloadDebianRootfs(callback: ProgressCallback): Boolean = false

    @Deprecated("Use installToContainer(3, url, name, callback)")
    fun downloadWineRootfs(callback: ProgressCallback): Boolean = false

    // ── Session spawn (old, mapped) ──────────────────────
    fun spawnSession(sessionId: Int, rows: Int, cols: Int): Boolean =
        spawnDefaultSession(sessionId, rows, cols)

    fun spawnSessionInRootfs(
        sessionId: Int,
        rows: Int,
        cols: Int,
        rootfsKind: Int
    ): Boolean = when (rootfsKind) {
        0 -> spawnSessionInContainer(sessionId, rows, cols, 1) //1
        1 -> spawnSessionInContainer(sessionId, rows, cols, 2) // 2
        2 -> spawnSessionInContainer(sessionId, rows, cols, 3) // 3
        else -> false
    }

    // ── PTY control (unchanged) ─────────────────────────
    external fun closeSession(sessionId: Int)
    external fun isSessionAlive(sessionId: Int): Boolean
    external fun setPtyWindowSize(sessionId: Int, rows: Int, cols: Int)
    external fun writeInput(sessionId: Int, bytes: ByteArray)

    // ── NEW generic installation ────────────────────────
    /** Install a rootfs tarball into container 1, 2, or 3. */
    external fun installToContainer(
        containerId: Int,
        url: String,
        tarballName: String,
        callback: ProgressCallback
    ): Boolean

    // ── NEW container checks ────────────────────────────
    /** Check if a specific container (1,2,3) has a valid rootfs. */
    external fun hasContainerRootfs(containerId: Int): Boolean

    /** Returns a bitmask: bit0=container1, bit1=container2, bit2=container3. */
    external fun getInstalledContainersMask(): Int

    /** Returns the first installed container ID (1,2,3) or 0 if none. */
    external fun getDefaultContainer(): Int

    // ── NEW session spawn ──────────────────────────────
    /** Spawn a shell in the first installed container (automatic default). */
    external fun spawnDefaultSession(
        sessionId: Int,
        rows: Int,
        cols: Int
    ): Boolean

    /** Spawn a shell in a specific container. */
    external fun spawnSessionInContainer(
        sessionId: Int,
        rows: Int,
        cols: Int,
        containerId: Int
    ): Boolean



    // This one can be removed if not used – it requires a local file path, not yet implemented
    // external fun extractLocalRootfs(
    //     rootfsKind: Int,
    //     archivePath: String,
    //     callback: ProgressCallback
    // ): Boolean
}

interface ProgressCallback {
    fun onProgress(pct: Int, msg: String)
}