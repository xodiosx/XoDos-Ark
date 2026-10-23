package app.xodos2.ui.drawer.pages

import android.content.Context
import android.util.Log
import app.xodos2.ui.runtime.NativeInstallCoordinator
import java.io.File

object DesktopDetector {

    /** Distro id used by AppScreen to mark a container as native (no PRoot). */
    const val NATIVE_DISTRO_ID = "XoDos-Ark"

    // Known session binaries and their display names.
    // Matches every DE / WM that can be installed from Termux's x11-repo.
    internal val knownBinaries = mapOf(
        "xfce4-session"       to "XFCE Desktop",
        "lxqt-session"        to "LXQt Desktop",
        "gnome-shell"         to "GNOME",
        "startplasma-x11"     to "KDE Plasma (X11)",
        "startplasma-wayland" to "KDE Plasma (Wayland)",
        "mate-session"        to "MATE",
        "cinnamon-session"    to "Cinnamon",
        "budgie-desktop"      to "Budgie",
        "startlxde"           to "LXDE",
        "startlxqt"           to "LXQt",
        "startxfce4"          to "XFCE",
        "enlightenment_start" to "Enlightenment",
        "openbox-session"     to "Openbox",
        "i3"                  to "i3 Window Manager",
        "fluxbox"             to "Fluxbox",
        "icewm-session"       to "IceWM"
    )

    // Battery status script that works without termux-api
    private val TERMUX_BATTERY_STATUS_SCRIPT = """
#!/data/data/app.xodos2/files/usr/bin/sh
set -e -u

SCRIPTNAME=termux-battery-status
show_usage () {
    echo "Usage: ${'$'}SCRIPTNAME"
    echo "Get the status of the device battery."
    exit 0
}

while getopts :h option
do
    case "${'$'}option" in
        h) show_usage;;
        ?) echo "${'$'}SCRIPTNAME: illegal option -${'$'}OPTARG"; exit 1;;
    esac
done
shift ${'$'}((OPTIND-1))

if [ ${'$'}# != 0 ]; then echo "${'$'}SCRIPTNAME: too many arguments"; exit 1; fi

BATTERY_DIR=""
for d in /sys/class/power_supply/battery /sys/class/power_supply/BAT0 /sys/class/power_supply/BAT1; do
    if [ -d "${'$'}d" ]; then
        BATTERY_DIR="${'$'}d"
        break
    fi
done

if [ -z "${'$'}BATTERY_DIR" ]; then
    echo "No battery information available" >&2
    exit 1
fi

STATUS=$(cat "${'$'}BATTERY_DIR/status" 2>/dev/null || echo "Unknown")
CAPACITY=$(cat "${'$'}BATTERY_DIR/capacity" 2>/dev/null || echo "0")
TEMP=$(cat "${'$'}BATTERY_DIR/temp" 2>/dev/null || echo "0")
VOLTAGE=$(cat "${'$'}BATTERY_DIR/voltage_now" 2>/dev/null || echo "0")

PLUGGED="UNPLUGGED"
if [ -f /sys/class/power_supply/usb/online ]; then
    USB_ONLINE=$(cat /sys/class/power_supply/usb/online 2>/dev/null)
    if [ "${'$'}USB_ONLINE" = "1" ]; then PLUGGED="USB"; fi
fi
if [ -f /sys/class/power_supply/ac/online ]; then
    AC_ONLINE=$(cat /sys/class/power_supply/ac/online 2>/dev/null)
    if [ "${'$'}AC_ONLINE" = "1" ]; then PLUGGED="AC"; fi
fi
if [ "${'$'}PLUGGED" = "UNPLUGGED" ] && [ -f "${'$'}BATTERY_DIR/charge_type" ]; then
    CHARGE_TYPE=$(cat "${'$'}BATTERY_DIR/charge_type" 2>/dev/null)
    case "${'$'}CHARGE_TYPE" in
        *USB*) PLUGGED="USB" ;;
        *AC*) PLUGGED="AC" ;;
        *Fast*|*Slow*|*Wireless*) PLUGGED="PLUGGED" ;;
    esac
fi

HEALTH="UNKNOWN"
if [ -f "${'$'}BATTERY_DIR/health" ]; then
    HEALTH=$(cat "${'$'}BATTERY_DIR/health" 2>/dev/null)
    case "${'$'}HEALTH" in
        Good|GOOD) HEALTH="GOOD" ;;
        Overheat|OVERHEAT) HEALTH="OVERHEAT" ;;
        Cold|COLD) HEALTH="COLD" ;;
        Dead|DEAD) HEALTH="DEAD" ;;
        Overvoltage|OVERVOLTAGE) HEALTH="OVERVOLTAGE" ;;
        Unspecified|UNSPECIFIED) HEALTH="UNSPECIFIED" ;;
    esac
fi

printf '{\n'
printf '  "health": "%s",\n' "${'$'}HEALTH"
printf '  "percentage": %s,\n' "${'$'}CAPACITY"
printf '  "plugged": "%s",\n' "${'$'}PLUGGED"
printf '  "status": "%s",\n' "${'$'}STATUS"
printf '  "temperature": %s,\n' "${'$'}TEMP"
printf '  "voltage": %s\n' "${'$'}VOLTAGE"
printf '}\n'
""".trimIndent()

    /**
     * True when [containerId] is marked as the native XoDos-Ark environment —
     * either via SharedPreferences ("XoDos-Ark") or the .rootfs_type marker.
     */
    fun isNativeContainer(context: Context, containerId: Int): Boolean {
    val containerDir = NativeInstallCoordinator.containerPath(context, containerId)

    // ── 1. Marker file is authoritative ───────────────────────────
    // Native containers always write "xodos-ark" here.
    for (name in listOf(".rootfs_type", "rootfs_type")) {
        val marker = File(containerDir, name)
        if (marker.exists()) {
            val txt = marker.readText().trim()
            Log.d("DesktopDetector", "isNative: $name='$txt'")
            if (isNativeDistroId(txt)) return true
        }
    }

    // ── 2. Saved pref (accepts aliases, not just the exact id) ────
    val saved = NativeInstallCoordinator.getContainerDistro(context, containerId)
    Log.d("DesktopDetector", "isNative: saved='$saved'")
    if (isNativeDistroId(saved)) return true

    // ── 3. Last resort: native prefix present, no rootfs dir ──────
    val hasNativeUsr = File(context.filesDir, "usr/bin").isDirectory
    val hasRootfs    = containerDir.isDirectory
    Log.d("DesktopDetector", "isNative: usr=$hasNativeUsr rootfs=$hasRootfs")
    if (hasNativeUsr && !hasRootfs) return true

    return false
}

/** Single source of truth for "this id means native". */
internal fun isNativeDistroId(id: String?): Boolean {
    val d = id?.trim().orEmpty()
    if (d.isEmpty()) return false
    return d.equals(NATIVE_DISTRO_ID, ignoreCase = true) ||
           d.contains("xodos-ark", ignoreCase = true) ||
           d.contains("xodos2",    ignoreCase = true) ||
           d.equals("native",      ignoreCase = true) ||
           d.equals("termux",      ignoreCase = true)
}
    /**
     * Returns [displayName, binaryName] for every desktop environment whose
     * starting binary exists.
     *
     * · Native container  → filesDir/usr/bin (+ filesDir/bin)
     * · Proot container   → <rootfs>/usr/bin (+ <rootfs>/bin), with the
     *                       XFCE-from-host wrapper fallback.
     */
    fun detectInstalled(context: Context, containerId: Int): List<Pair<String, String>> {
        if (isNativeContainer(context, containerId)) {
            return detectNativeInstalled(context)
        }
        return detectProotInstalled(context, containerId)
    }

    // ── Native ────────────────────────────────────────────────
  private fun detectNativeInstalled(context: Context): List<Pair<String, String>> {
    val binDirs = listOf("usr/bin", "bin").mapNotNull { sub ->
        val dir = File(context.filesDir, sub)
        if (dir.isDirectory) dir else null
    }
    Log.d("DesktopDetector", "Native bin dirs: ${binDirs.map { it.absolutePath }}")
    if (binDirs.isEmpty()) return emptyList()

    val detected = knownBinaries.mapNotNull { (binary, name) ->
        val exists = binDirs.any { File(it, binary).exists() }
        if (exists) {
            Log.d("DesktopDetector", "Native: found $binary -> $name")
            name to binary
        } else null
    }

    // Native XFCE installed → make sure termux-battery-status shim is present.
    // Idempotent: rewrites the script only when XFCE is actually detected.
    if (detected.any { it.second == "xfce4-session" }) {
        writeBatteryStatusScript(context)
    }

    return detected
}
    // ── Proot container ───────────────────────────────────────
    private fun detectProotInstalled(context: Context, containerId: Int): List<Pair<String, String>> {
        val rootfs = NativeInstallCoordinator.containerPath(context, containerId)
        Log.d("DesktopDetector", "Rootfs path: $rootfs")

        val binDirs = listOf("usr/bin", "bin").mapNotNull { subPath ->
            val dir = File(rootfs, subPath)
            Log.d("DesktopDetector", "Checking dir: ${dir.absolutePath} isDirectory=${dir.isDirectory}")
            if (dir.isDirectory) dir else null
        }
        if (binDirs.isEmpty()) return emptyList()

        val detected = knownBinaries.mapNotNull { (binary, name) ->
            val exists = binDirs.any { binDir ->
                val file = File(binDir, binary)
                Log.d("DesktopDetector", "Check ${file.absolutePath} exists=${file.exists()}")
                file.exists()
            }
            if (exists) name to binary else null
        }.toMutableList()

        // If XFCE not detected, try fallback using host binaries from files/usr/bin
        if (detected.none { it.second == "xfce4-session" }) {
            Log.d("DesktopDetector", "XFCE not found, checking host files/usr/bin")
            val hostUsrBin = File(context.filesDir, "usr/bin")
            val hostXfceSession = File(hostUsrBin, "xfce4-session")
            val hostStartxfce4 = File(hostUsrBin, "startxfce4")

            if (hostXfceSession.exists() || hostStartxfce4.exists()) {
                val hostBinaryPath = if (hostXfceSession.exists()) {
                    hostXfceSession.absolutePath
                } else {
                    hostStartxfce4.absolutePath
                }
                Log.d("DesktopDetector", "Host binary found: $hostBinaryPath")

                writeBatteryStatusScript(context)

                val containerUsrBin = File(rootfs, "usr/bin")
                if (!containerUsrBin.exists()) containerUsrBin.mkdirs()

                val wrapperFile = File(containerUsrBin, "xfce4-session")
                val wrapperScript = buildXfce4WrapperScript(hostBinaryPath)
                try {
                    wrapperFile.writeText(wrapperScript)
                    wrapperFile.setExecutable(true, false)
                    Log.d("DesktopDetector", "Created wrapper at ${wrapperFile.absolutePath}")
                    if (wrapperFile.exists()) {
                        detected.add("XFCE Desktop" to "xfce4-session")
                    }
                } catch (e: Exception) {
                    Log.e("DesktopDetector", "Failed to create wrapper", e)
                }
            } else {
                Log.d("DesktopDetector", "No host XFCE binaries found")
            }
        }

        return detected
    }

    private fun writeBatteryStatusScript(context: Context) {
        val binDir = File(context.filesDir, "usr/bin")
        if (!binDir.exists()) binDir.mkdirs()
        val batteryScriptFile = File(binDir, "termux-battery-status")
        if (batteryScriptFile.exists()) batteryScriptFile.delete()
        try {
            batteryScriptFile.writeText(TERMUX_BATTERY_STATUS_SCRIPT)
            batteryScriptFile.setExecutable(true, false)
            Log.d("DesktopDetector", "Battery status script written to ${batteryScriptFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("DesktopDetector", "Failed to write battery script", e)
        }
    }

    private fun buildXfce4WrapperScript(hostBinaryPath: String): String {
        return """
#!/bin/
# Xfce4 session wrapper created by XoDos2
xfce4-session 
""".trimIndent()
    }

    /**
     * Returns a shell script that launches [binary].
     *
     * @param isNative  when true, produces a Termux-style native launch
     *                  script (PREFIX/HOME/PATH/LD_LIBRARY_PATH, no PRoot).
     */
    fun defaultLaunchScript(binary: String, isNative: Boolean = false): String {
        if (isNative) return nativeLaunchScript(binary)

        // ── PRoot / distro container ─────────────────────────────
        val commonSetup = """
        # Common environment
        if [ -f /.x11 ]; then
            export GDK_BACKEND='x11'
            export QT_QPA_PLATFORM='xcb'
            export XDG_SESSION_TYPE='x11'
            unset WAYLAND_DISPLAY
        elif [ -f /.wayland ]; then
            export WAYLAND_DISPLAY='wayland-xodos2'
            export GDK_BACKEND='wayland'
            export QT_QPA_PLATFORM='wayland'
            export XDG_SESSION_TYPE='wayland'
        fi
             
        export DISPLAY=:0
        export PULSE_SERVER=127.0.0.1
        
        # Ensure old DBus instances are stopped cleanly
        killall -9 dbus-daemon dbus-launch 2>/dev/null
        rm -f /run/dbus/pid && mkdir -p /run/dbus 
        """.trimIndent()

        val specific = when (binary) {
            "gnome-shell" -> """
                # GNOME workarounds
                dbus-daemon --system --fork 2>/dev/null
                killall -9 gnome-session-binary metacity gnome-panel 2>/dev/null              
                
                export XDG_CURRENT_DESKTOP=GNOME
                export DESKTOP_SESSION=gnome
                export XDG_SESSION_DESKTOP=gnome
                
                if [ "${'$'}XDG_SESSION_TYPE" = "wayland" ]; then
                    exec dbus-run-session gnome-shell --wayland &
                else
                    exec dbus-run-session gnome-shell --x11 &
                fi
                """.trimIndent()

            "startplasma-x11" -> """
                killall -9 startplasma-x11 startplasma-wayland startplasma* 2>/dev/null
                export DESKTOP_SESSION=plasma
                export XDG_CURRENT_DESKTOP=KDE
                exec dbus-run-session startplasma-x11 &
                """.trimIndent()

            "startplasma-wayland" -> """
                killall -9 startplasma-x11 startplasma-wayland startplasma* 2>/dev/null
                export DESKTOP_SESSION=plasma
                export XDG_CURRENT_DESKTOP=KDE
                exec dbus-run-session startplasma-wayland &
                """.trimIndent()

            "xfce4-session" -> """
                killall -9 xfce4-session xfce4* 2>/dev/null
                if [ -f /data/data/app.xodos2/files/usr/bin/xfce4-session ]; then
                export PREFIX="/data/data/app.xodos2/files/usr"
                export PATH=${'$'}PATH:/data/data/app.xodos2/files/usr/bin
                 export XDG_DATA_DIRS="/usr/local/share:/usr/share:/data/data/app.xodos2/files/usr/local/share:/data/data/app.xodos2/files/usr/share"
                 export XDG_CONFIG_DIRS='/etc/xdg:/data/data/app.xodos2/files/usr/etc/xdg'
                 fi
                export XDG_CURRENT_DESKTOP=XFCE
                export XDG_SESSION_DESKTOP=xfce
                dbus-launch xfce4-session &
                """.trimIndent()

            "lxqt-session" -> """
                killall -9 lxqt-session lxqt* 2>/dev/null
                export XDG_CURRENT_DESKTOP=LXQt
                exec dbus-run-session lxqt-session &
                """.trimIndent()

            "mate-session" -> """
                killall -9 mate-session mate* 2>/dev/null
                export XDG_CURRENT_DESKTOP=MATE
                exec dbus-run-session mate-session &
                """.trimIndent()

            "cinnamon-session" -> """
                killall -9 cinnamon-session cinnamon* 2>/dev/null
                export XDG_CURRENT_DESKTOP=Cinnamon
                exec dbus-run-session cinnamon-session &
                """.trimIndent()

            else -> "exec dbus-run-session $binary"
        }

        return "$commonSetup\n\n$specific"
    }

    /**
     * Termux-style native launch script. Runs in the app's own PREFIX.
     * Detects X11 vs Wayland from the app's container markers.
     *
     * Uses a Kotlin `sd` variable for the shell `$` so no fragile
     * `${'$'}` sequences need to survive copy-paste.
     */
    private fun nativeLaunchScript(binary: String): String {
        val prefix = "/data/data/app.xodos2/files/usr"
        val home   = "/data/data/app.xodos2/files/home"
        val sd     = "\u0024"   // literal shell dollar sign

        val common = """
            # Native XoDos-Ark (Termux-style) environment
            export PREFIX="$prefix"
            export HOME="$home"
            export PATH="$prefix/bin:/system/bin:/system/xbin"
            #export LD_LIBRARY_PATH="$prefix/lib"
            #export SHELL="$prefix/bin/bash"
            export PULSE_SERVER=127.0.0.1
            export XDG_RUNTIME_DIR="$prefix/tmp"
            export TMPDIR="$prefix/tmp"
            export MOZ_FAKE_NO_SANDBOX=1

            mkdir -p "$home" "$prefix/tmp"

            _XODOS_SESSION="x11"
            for _cid in 1 2 3; do
                if [ -f "/data/data/app.xodos2/files/containers/${sd}_cid/.wayland" ]; then
                    _XODOS_SESSION="wayland"
                    break
                fi
            done

            if [ "${sd}_XODOS_SESSION" = "x11" ]; then
                export DISPLAY=:0
                export GDK_BACKEND='x11'
                export QT_QPA_PLATFORM='xcb'
                export XDG_SESSION_TYPE='x11'
                unset WAYLAND_DISPLAY
            else
                export WAYLAND_DISPLAY='wayland-xodos2'
                export GDK_BACKEND='wayland'
                export QT_QPA_PLATFORM='wayland'
                export XDG_SESSION_TYPE='wayland'
                unset DISPLAY
            fi
            . ${'$'}PREFIX/opt/drv

            killall -9 dbus-daemon dbus-launch 2>/dev/null || true
        """.trimIndent()

        val specific = when (binary) {
            "gnome-shell" -> """
                killall -9 gnome-session-binary metacity gnome-panel 2>/dev/null || true
                export XDG_CURRENT_DESKTOP=GNOME
                export DESKTOP_SESSION=gnome
                export XDG_SESSION_DESKTOP=gnome

                if [ "${sd}XDG_SESSION_TYPE" = "wayland" ]; then
                    dbus-run-session gnome-shell --wayland &
                else
                    dbus-run-session gnome-shell --x11 &
                fi
            """.trimIndent()

            "startplasma-x11" -> """
                killall -9 startplasma-x11 startplasma-wayland startplasma* 2>/dev/null || true
                export DESKTOP_SESSION=plasma
                export XDG_CURRENT_DESKTOP=KDE
                dbus-run-session startplasma-x11 &
            """.trimIndent()

            "startplasma-wayland" -> """
                killall -9 startplasma-x11 startplasma-wayland startplasma* 2>/dev/null || true
                export DESKTOP_SESSION=plasma
                export XDG_CURRENT_DESKTOP=KDE
                dbus-run-session startplasma-wayland &
            """.trimIndent()

            "xfce4-session" -> """
                killall -9 xfce4-session xfce4-* 2>/dev/null || true
                export XDG_CURRENT_DESKTOP=XFCE
                export XDG_SESSION_DESKTOP=xfce
                dbus-launch --exit-with-session xfce4-session &
            """.trimIndent()

            "lxqt-session" -> """
                killall -9 lxqt-session lxqt-* 2>/dev/null || true
                export XDG_CURRENT_DESKTOP=LXQt
                dbus-launch --exit-with-session lxqt-session &
            """.trimIndent()

            "mate-session" -> """
                killall -9 mate-session mate-* 2>/dev/null || true
                export XDG_CURRENT_DESKTOP=MATE
                dbus-launch --exit-with-session mate-session &
            """.trimIndent()

            "cinnamon-session" -> """
                killall -9 cinnamon-session cinnamon-* 2>/dev/null || true
                export XDG_CURRENT_DESKTOP=Cinnamon
                dbus-run-session cinnamon-session &
            """.trimIndent()

            // Generic fallback for Budgie, LXDE, Enlightenment,
            // Openbox, i3, Fluxbox, IceWM, and anything else.
            else -> "dbus-launch --exit-with-session $binary &"
        }

        return "$common\n\n$specific"
    }
}