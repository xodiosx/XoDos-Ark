package app.xodos2

import com.termux.x11.LorieApp

class xodos2Application : LorieApp() {
    override fun onCreate() {
        super.onCreate()                     // ← LorieApp.onCreate: attaches Prefs, notification channel, broadcast listener
        X11Runtime.initInMainProcess(this)
    }
}