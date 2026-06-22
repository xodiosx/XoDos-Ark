package app.xodos2

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import com.termux.view.DisplayableTermSession
import com.termux.view.TerminalView
import java.io.File

/**
 * Proot PTY ([NativeBridge]) wired into [TerminalEmulator] without the default subprocess spawn.
 */
class RustPtySession(
    private val context: Context,
    sessionClient: TerminalSessionClient,
    private val terminalView: TerminalView,
    val sessionId: Int
) : TerminalOutput(), DisplayableTermSession {

    private val sessionClient: TerminalSessionClient = sessionClient
    private var emulator: TerminalEmulator? = null
    private val utf8InputBuffer = ByteArray(5)
    private var didAppendWelcomeBanner: Boolean = false

    private var appliedPtyRows: Int = -1
    private var appliedPtyCols: Int = -1

    private fun syncPtyKernelWindowSize(rows: Int, cols: Int) {
        if (rows == appliedPtyRows && cols == appliedPtyCols) return
        appliedPtyRows = rows
        appliedPtyCols = cols
        NativeBridge.setPtyWindowSize(sessionId, rows, cols)
    }

    override fun updateSize(columns: Int, rows: Int) {
        val rootfsKind = TerminalSessionIds.rootfsKindForNativeId(sessionId)
        if (!NativeBridge.spawnSessionInRootfs(sessionId, rows, columns, rootfsKind)) {
            Log.e(TAG, "spawnSession failed ($sessionId)")
            return
        }
        syncPtyKernelWindowSize(rows, columns)
        if (emulator == null) {
            emulator = TerminalEmulator(
                this,
                columns,
                rows,
                4000, // high transcript rows
                sessionClient
            )
            if (!didAppendWelcomeBanner) {
                didAppendWelcomeBanner = true

                // --- Determine distro name from container's .rootfs_type file ---
                val containerId = when (TerminalSessionIds.namespaceOf(sessionId)) {
                    TerminalSessionIds.NS_ARCH   -> 1
                    TerminalSessionIds.NS_DEBIAN -> 2
                    TerminalSessionIds.NS_WINE   -> 3
                    else -> 0
                }
                var distroName = ""
                if (containerId > 0) {
                    val rootfsTypeFile = File(context.filesDir, "containers/$containerId/.rootfs_type")
                    if (rootfsTypeFile.exists()) {
                        distroName = rootfsTypeFile.readText().trim()
                    }
                }
                // Fallback to generic name if file missing/empty
                if (distroName.isEmpty()) {
                    distroName = when (TerminalSessionIds.namespaceOf(sessionId)) {
                        TerminalSessionIds.NS_ARCH   -> "Container 1"
                        TerminalSessionIds.NS_DEBIAN -> "Container 2"
                        TerminalSessionIds.NS_WINE   -> "Container 3"
                        else -> "Unknown"
                    }
                }

                val welcome = buildWelcomeLine(sessionId, distroName)
                emulator!!.append(welcome, welcome.size)
            }
        } else {
            emulator!!.resize(columns, rows)
        }
        PtyOutputRelay.bind(this, terminalView)
    }

    override fun getEmulator(): TerminalEmulator = emulator
        ?: throw IllegalStateException("Terminal emulator not initialized (call updateSize first)")

    fun emulatorOrNull(): TerminalEmulator? = emulator

    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (count <= 0) return
        NativeBridge.writeInput(sessionId, data.copyOfRange(offset, offset + count))
    }

    override fun writeCodePoint(prependEscape: Boolean, codePoint: Int) {
        if (codePoint > 1114111 || codePoint in 0xD800..0xDFFF) {
            throw IllegalArgumentException("Invalid code point: $codePoint")
        }
        var pos = 0
        if (prependEscape) utf8InputBuffer[pos++] = 27
        when {
            codePoint <= 0b1111111 -> utf8InputBuffer[pos++] = codePoint.toByte()
            codePoint <= 0b11111111111 -> {
                utf8InputBuffer[pos++] = (0b11000000 or (codePoint shr 6)).toByte()
                utf8InputBuffer[pos++] = (0b10000000 or (codePoint and 0b111111)).toByte()
            }
            codePoint <= 0b1111111111111111 -> {
                utf8InputBuffer[pos++] = (0b11100000 or (codePoint shr 12)).toByte()
                utf8InputBuffer[pos++] = (0b10000000 or ((codePoint shr 6) and 0b111111)).toByte()
                utf8InputBuffer[pos++] = (0b10000000 or (codePoint and 0b111111)).toByte()
            }
            else -> {
                utf8InputBuffer[pos++] = (0b11110000 or (codePoint shr 18)).toByte()
                utf8InputBuffer[pos++] = (0b10000000 or ((codePoint shr 12) and 0b111111)).toByte()
                utf8InputBuffer[pos++] = (0b10000000 or ((codePoint shr 6) and 0b111111)).toByte()
                utf8InputBuffer[pos++] = (0b10000000 or (codePoint and 0b111111)).toByte()
            }
        }
        write(utf8InputBuffer, 0, pos)
    }

    override fun titleChanged(oldTitle: String?, newTitle: String?) {
        terminalView.postInvalidate()
    }

    override fun onCopyTextToClipboard(text: String?) {
        if (text.isNullOrEmpty()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("", text))
    }

    override fun onPasteTextFromClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        NativeBridge.writeInput(sessionId, clip.toByteArray(Charsets.UTF_8))
    }

    override fun onBell() {
        // Optional: system beep; keep quiet on mobile.
    }

    override fun onColorsChanged() {
        terminalView.postInvalidate()
    }

    private companion object {
        private const val TAG = "RustPtySession"

        /** Builds the colourful "Welcome to XoDos-Ark # distro" line. */
        private fun buildWelcomeLine(sessionId: Int, distroName: String): ByteArray {
            // Choose colour based on distro name (or namespace fallback)
            val rgb = when (distroName.lowercase()) {
                "archlinux", "arch" -> intArrayOf(0x17, 0x93, 0xD1)    // blue
                "debian"            -> intArrayOf(0x8A, 0x2B, 0xE2)    // purple 
                "nixos"            -> intArrayOf(0x8A, 0x2B, 0xE2)    // purple 
                "ubuntu"            -> intArrayOf(0xE9, 0x54, 0x20)    // orange
                "fedora"            -> intArrayOf(0x29, 0x47, 0xAB)    // dark blue
                "alpine"            -> intArrayOf(0x0D, 0x59, 0x7F)    // teal
                "void"              -> intArrayOf(0x47, 0x8C, 0x5C)    // green
                "kali"              -> intArrayOf(0x36, 0x7D, 0xA7)    // dark cyan
                "opensuse"          -> intArrayOf(0x73, 0xBA, 0x25)    // green
                "manjaro"           -> intArrayOf(0x33, 0xBE, 0x5E)    // green
                "deepin"            -> intArrayOf(0x00, 0x94, 0xD1)    // light blue
                "almalinux"         -> intArrayOf(0xEB, 0x6C, 0x1A)    // orange
                "artix"             -> intArrayOf(0x2E, 0x85, 0xC1)    // blue
                "rocky"             -> intArrayOf(0x6F, 0xB7, 0x3F)    // green
                "trisquel"          -> intArrayOf(0xF7, 0x9D, 0x32)    // orange
                 "linux"            -> intArrayOf(0xD7, 0x0A, 0x53)    // red
                else -> when (TerminalSessionIds.namespaceOf(sessionId)) {
                    TerminalSessionIds.NS_ARCH   -> intArrayOf(0x17, 0x93, 0xD1)
                    TerminalSessionIds.NS_DEBIAN -> intArrayOf(0x8A, 0x2B, 0xE2) // purple fallback
                    TerminalSessionIds.NS_WINE   -> intArrayOf(0x00, 0xFF, 0x00)
                    else -> intArrayOf(0x17, 0x93, 0xD1)
                }
            }
            val (r, g, b) = rgb
            // Capitalise first letter for display
            val displayName = distroName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            val s = "\u001b[38;2;${r};${g};${b}mWelcome to XoDos-Ark # ${displayName}\u001b[0m\n\r"
            return s.toByteArray(Charsets.UTF_8)
        }
    }
}