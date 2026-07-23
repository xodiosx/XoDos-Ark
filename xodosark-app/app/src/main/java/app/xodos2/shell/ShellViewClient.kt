package app.xodos2.shell

import android.content.Context
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.view.DisplayableTermSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/** [TerminalViewClient] for embedded [TerminalView]: IME focus, pinch zoom text size. */
class ShellViewClient(
    private val terminalView: TerminalView
) : TerminalViewClient {

    // --- Added variables for Extra Keys Modifiers ---
    var ctrlActive = false
    var altActive = false
    var onModifierReset: (() -> Unit)? = null
    // ------------------------------------------------

    override fun onScale(scaleFactor: Float): Float {
        val renderer = terminalView.mRenderer ?: return scaleFactor
        val metrics = terminalView.resources.displayMetrics
        val stepPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, metrics).toInt().coerceAtLeast(1)
        
        // Your custom extreme zoom limits
        val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 2f, metrics).toInt().coerceAtLeast(2)
        val maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 32f, metrics).toInt().coerceAtLeast(minPx + 1)
        
        val cur = renderer.getTextSizePx()
        return when {
            scaleFactor > 1.1f -> {
                terminalView.setTextSize((cur + stepPx).coerceAtMost(maxPx))
                1.0f
            }
            scaleFactor < 0.9f -> {
                terminalView.setTextSize((cur - stepPx).coerceAtLeast(minPx))
                1.0f
            }
            else -> scaleFactor
        }
    }

    // Notice the "?" after MotionEvent. This fixes the compiler error if the library expects a nullable type.
    override fun onSingleTapUp(e: MotionEvent?) {
        val imm = terminalView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        terminalView.post {
            terminalView.requestFocus()
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: DisplayableTermSession): Boolean = false
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    
    // --- Updated methods to feed Modifier states to Termux ---
    override fun readControlKey(): Boolean = ctrlActive
    override fun readAltKey(): Boolean = altActive
    
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: DisplayableTermSession): Boolean {
        // If a modifier is active when the user types a character, 
        // reset the modifier state and notify the UI to update the button colors.
        if (ctrlActive || altActive) {
            ctrlActive = false
            altActive = false
            onModifierReset?.invoke()
        }
        // Return false so Termux natively processes the character + modifiers
        return false 
    }
    // ---------------------------------------------------------

    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onEmulatorSet() {}
    
    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, Log.getStackTraceString(e))
    }
}
