package app.shunt.app

import android.app.Application
import app.shunt.app.di.AppContainer

/**
 * Holds the app-wide [AppContainer]. Registered in the manifest so anything
 * with a Context can reach the single dependency-resolution point.
 */
class ShuntApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashReporter()
        container = AppContainer(this)
    }

    /**
     * Persist the stack trace of any uncaught exception so the next launch can
     * show it on screen (see MainActivity). This is how we surface crashes on a
     * device we can't attach a debugger to — the alternative is a blank screen
     * with no clue. Installed before anything else so it also catches failures
     * during container construction.
     */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                getSharedPreferences(DIAGNOSTICS_PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_LAST_CRASH, throwable.stackTraceToString())
                    .putLong(KEY_LAST_CRASH_AT, System.currentTimeMillis())
                    .commit() // synchronous: the process is about to die
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val DIAGNOSTICS_PREFS = "diagnostics"
        const val KEY_LAST_CRASH = "last_crash"
        const val KEY_LAST_CRASH_AT = "last_crash_at"
    }
}
