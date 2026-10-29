package org.openmw

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.alpha3.launcher.engine.Engine
import org.alpha3.launcher.utils.Platform
import org.alpha3.launcher.utils.SystemInfo
import org.alpha3.launcher.paths.OpenMWPathsAndroid

const val OPENMW_MAIN_LIB = "libopenmw.so"
// Stock OpenMW, cpp/openmw-upstream
const val OPENMW_UPSTREAM_MAIN_LIB = "libopenmw_upstream.so"
const val UQM_MAIN_LIB = "libuqm.so"
const val DETHRACE_MAIN_LIB = "libdethrace.so"

/**
 * Looked up from native code (cpp/Alpha3/java.cpp, FindClass("org/openmw/Constants")), so the
 * class name and the static field must stay. Keep in sync with OpenMWPaths.RANDOM_NUM.
 */
object Constants {
    const val RANDOM_NUM = "Alpha-22556"
}

@HiltAndroidApp
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this

        // Shared paths (org.alpha3.launcher.paths.OpenMWPaths) need the application context
        OpenMWPathsAndroid.init(this)
        Engine.bridge = SdlEngineBridge(this)
        SystemInfo.services = AndroidSystemServices(this)
        Platform.features = AndroidPlatformFeatures(this)

        // How the previous run ended: the system's tombstone and exit record, saved next to our own crash report
        CrashReports.collectAsync(this)

        if (getProcessName() == packageName) org.openmw.plugins.Plugins.loadAll(this)
    }

    companion object {
        lateinit var app: MyApp
    }
}
