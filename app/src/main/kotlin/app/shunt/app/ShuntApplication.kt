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
        container = AppContainer()
    }
}
