/**
 * Fast path: unload any existing sles-source, load fresh at 48 kHz
 * (16 kHz as shell-level fallback only if 48 kHz is rejected by the ROM),
 * pin as default. Everything runs in ONE shell process — ~150 ms.
 *
 * Why unload first: Rust's supervisor loads module-sles-source at 16 kHz
 * on every PA start (safety net). Without an unload here the 16 kHz source
 * stays and we'd only pin it. Unload → reload at 48 kHz upgrades in place.
 *
 * If both 48 k and 16 k fail here, the toggle shows the restart dialog.
 * Rust then reloads at 16 k on next PA start — the user still has a mic.
 */
suspend fun hotApplyAndGetSource(enabled: Boolean): Pair<Boolean, String?> {
    if (!enabled) {
        safeUnload()
        return true to null
    }

    val (exit, out) = sh(
        """
        for id in $(pactl list short modules 2>/dev/null | awk '/sles-source/ {print $1}'); do
            pactl unload-module $id 2>/dev/null
        done
        pactl load-module module-sles-source rate=48000 channels=1 source_name=$SOURCE_NAME >/dev/null 2>&1 || \
        pactl load-module module-sles-source rate=16000 channels=1 source_name=$SOURCE_NAME >/dev/null 2>&1 || \
        exit 1
        pactl set-default-source $SOURCE_NAME >/dev/null 2>&1 || true
        pactl list short sources 2>/dev/null
        """.trimIndent()
    )

    val name = out.lines()
        .firstOrNull { it.contains(SOURCE_NAME) }?.split(Regex("\\s+"))?.getOrNull(1)
        ?: out.lines().firstOrNull { it.contains("sles-source") }
            ?.split(Regex("\\s+"))?.getOrNull(1)
    return (exit == 0) to name
}