package app.xodos2.ui.drawer.pages

import android.content.Context
import android.util.Log
import app.xodos2.ui.runtime.NativeInstallCoordinator
import java.io.File

object DesktopDetector {

    // Known session binaries and their display names
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
        "enlightenment_start" to "Enlightenment"
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

# Try to find a battery directory in sysfs
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

# Read basic battery info
STATUS=$(cat "${'$'}BATTERY_DIR/status" 2>/dev/null || echo "Unknown")
CAPACITY=$(cat "${'$'}BATTERY_DIR/capacity" 2>/dev/null || echo "0")
TEMP=$(cat "${'$'}BATTERY_DIR/temp" 2>/dev/null || echo "0")
VOLTAGE=$(cat "${'$'}BATTERY_DIR/voltage_now" 2>/dev/null || echo "0")

# Determine plugged state (default to unplugged)
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

# Determine health
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

# Output JSON similar to termux-api
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
     * Returns a list of [displayName, binaryName] for every desktop
     * environment whose starting binary exists in the container’s /usr/bin.
     * If XFCE is not found but its binaries exist in the host files/usr/bin,
     * a wrapper script is created inside the container so it can be launched.
     * Also writes a fixed battery status script if XFCE host binaries are detected.
     */
    fun detectInstalled(context: Context, containerId: Int): List<Pair<String, String>> {
        val rootfs = NativeInstallCoordinator.containerPath(context, containerId)
        Log.d("DesktopDetector", "Rootfs path: $rootfs")

        val binDirs = listOf("usr/bin", "bin").mapNotNull { subPath ->
            val dir = File(rootfs, subPath)
            Log.d("DesktopDetector", "Checking dir: ${dir.absolutePath} isDirectory=${dir.isDirectory}")
            if (dir.isDirectory) dir else null
        }
        if (binDirs.isEmpty()) return emptyList()

        // First, try normal detection
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

                // Write the fixed battery status script (overwrites any existing)
                writeBatteryStatusScript(context)

                // Create wrapper script inside container's /usr/bin
                val containerUsrBin = File(rootfs, "usr/bin")
                if (!containerUsrBin.exists()) {
                    containerUsrBin.mkdirs()
                }

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

    /**
     * Writes a fixed termux-battery-status script that reads battery info
     * directly from sysfs, bypassing the missing termux-api binary.
     * Deletes the old file if it exists, then writes the new script with
     * executable permission.
     */
    private fun writeBatteryStatusScript(context: Context) {
        val binDir = File(context.filesDir, "usr/bin")
        if (!binDir.exists()) {
            binDir.mkdirs()
        }
        val batteryScriptFile = File(binDir, "termux-battery-status")

        // Delete old file if present
        if (batteryScriptFile.exists()) {
            batteryScriptFile.delete()
        }

        try {
            batteryScriptFile.writeText(TERMUX_BATTERY_STATUS_SCRIPT)
            batteryScriptFile.setExecutable(true, false)  // owner execute
            batteryScriptFile.setReadable(true, false)    // owner readable
            batteryScriptFile.setWritable(true, false)    // owner writable
            Log.d("DesktopDetector", "Battery status script written to ${batteryScriptFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("DesktopDetector", "Failed to write battery script", e)
        }
    }

    /**
     * Builds a wrapper shell script that calls the host XFCE binary directly.
     * The host path is accessible inside the container because /data is bind-mounted.
     */
    private fun buildXfce4WrapperScript(hostBinaryPath: String): String {
        return """
#!/system/bin/sh
# Xfce4 session wrapper created by XoDos2
# Calls host binary: $hostBinaryPath

HOST_BIN="${'$'}{hostBinaryPath}"

if [ ! -x "${'$'}HOST_BIN" ]; then
    echo "Host binary not found: ${'$'}HOST_BIN" >&2
    exit 1
fi

# Basic environment setup
export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-/tmp/runtime-root}"
mkdir -p "${'$'}XDG_RUNTIME_DIR"
chmod 700 "${'$'}XDG_RUNTIME_DIR"

# Directly execute the host binary (no additional dbus wrapping)
exec "${'$'}HOST_BIN" "${'$'}@"
""".trimIndent()
    }

    /**
     * Returns a default shell script that sets up the environment
     * and launches the given session binary.
     * Includes common PRoot / container workarounds.
     */
    fun defaultLaunchScript(binary: String): String {
        // Common preamble for most desktops
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

        // Per‑binary tweaks
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
                export XDG_CURRENT_DESKTOP=XFCE
                exec dbus-run-session xfce4-session &
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
}