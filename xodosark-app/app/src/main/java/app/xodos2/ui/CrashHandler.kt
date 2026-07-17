package app.xodos2.ui

import android.content.Context
import android.content.Intent

object CrashHandler {
    fun install(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(sw))
            val stackTrace = sw.toString()

            val intent = Intent(context, CrashActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("EXTRA_STACK_TRACE", stackTrace)
            }
            context.startActivity(intent)

            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
    }
}