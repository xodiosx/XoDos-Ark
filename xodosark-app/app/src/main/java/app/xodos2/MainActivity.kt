package app.xodos2

import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.xodos2.wayland.input.HardwareKeyEventPolicy
import app.xodos2.wayland.input.HardwareKeyboardRouter
import app.xodos2.wayland.input.InputRouteState
import app.xodos2.ui.AppScreen
import app.xodos2.ui.theme.xodos2Theme
import app.xodos2.ui.CrashHandler

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_OPEN_TERMINAL = "app.xodos2.action.OPEN_TERMINAL"
    }

    private val hardwareKeyboardRouter = HardwareKeyboardRouter()
    private var startInTerminal by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        startInTerminal = intent?.action == ACTION_OPEN_TERMINAL

        // ── Make the Activity window transparent so the terminal's
        //    translucent background (and the wallpaper behind it) can show.
        window.setBackgroundDrawableResource(android.R.color.transparent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Let Compose draw edge-to-edge and let the wallpaper show through gaps.
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        // Optional: dim the wallpaper slightly for readability.
        // Remove this line if you want 100% wallpaper visibility.
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        CrashHandler.install(this)

        setContent {
            xodos2Theme {
                AppScreen(startInTerminal = startInTerminal)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startInTerminal = intent.action == ACTION_OPEN_TERMINAL
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (hardwareKeyboardRouter.handleHardwareKeyboardEvent(event)) return true
        if (!InputRouteState.waylandVisible &&
            !InputRouteState.lorieX11DisplayVisible &&
            HardwareKeyEventPolicy.isLikelyFromHardwareKeyboard(event)) {
            val tv = InputRouteState.shellTerminalView
            if (tv != null && !tv.hasFocus()) {
                tv.requestFocus()
                if (tv.dispatchKeyEvent(event)) return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (hardwareKeyboardRouter.handleHardwareKeyboardEvent(event)) return true
        if (!InputRouteState.waylandVisible &&
            !InputRouteState.lorieX11DisplayVisible &&
            HardwareKeyEventPolicy.isLikelyFromHardwareKeyboard(event)) {
            val tv = InputRouteState.shellTerminalView
            if (tv != null && !tv.hasFocus()) {
                tv.requestFocus()
                if (tv.dispatchKeyEvent(event)) return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (hardwareKeyboardRouter.handleHardwareKeyboardEvent(event)) return true
        if (!InputRouteState.waylandVisible &&
            !InputRouteState.lorieX11DisplayVisible &&
            HardwareKeyEventPolicy.isLikelyFromHardwareKeyboard(event)) {
            val tv = InputRouteState.shellTerminalView
            if (tv != null && !tv.hasFocus()) {
                tv.requestFocus()
                if (tv.dispatchKeyShortcutEvent(event)) return true
            }
        }
        return super.onKeyShortcut(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}