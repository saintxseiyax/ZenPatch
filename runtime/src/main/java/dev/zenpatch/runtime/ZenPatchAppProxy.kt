package dev.zenpatch.runtime

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import timber.log.Timber
import timber.log.Timber.DebugTree
import java.util.Properties

/**
 * Application proxy injected into patched APKs as the new Application class.
 * Bootstraps ZenPatch runtime before delegating to the original Application.
 *
 * Boot sequence in attachBaseContext():
 *   1. Init Timber logging
 *   2. Delegate to ZenPatchRuntime.init() for full bootstrap
 *   3. Forward to original Application class
 */
class ZenPatchAppProxy : Application() {

    private var originalApp: Application? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        // Initialize logging
        if (isDebugBuild()) Timber.plant(DebugTree())

        Timber.d("ZenPatchAppProxy.attachBaseContext starting…")

        try {
            val config = readConfig(base)
            val originalAppClass = config.getProperty("original_application")
            ZenPatchRuntime.init(base, originalAppClass)
        } catch (e: Exception) {
            // Graceful degradation: if bootstrap fails, still start the original app
            Timber.e(e, "ZenPatch bootstrap failed, starting original app without hooks")
        }

        // Delegate to original application
        createAndAttachOriginalApp(base)
    }

    private fun readConfig(context: Context): Properties {
        val props = Properties()
        return try {
            context.assets.open("zenpatch/config.properties").use { stream ->
                props.load(stream)
            }
            props
        } catch (e: Exception) {
            Timber.w("Config properties not found, using defaults")
            props
        }
    }

    private fun createAndAttachOriginalApp(base: Context) {
        val config = readConfig(base)
        val originalAppClassName = config.getProperty("original_application") ?: return

        try {
            val originalAppClass = classLoader.loadClass(originalAppClassName)
            val app = originalAppClass.getDeclaredConstructor().newInstance() as Application

            // Use reflection to call attachBaseContext on the original app
            val attachMethod = Application::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            attachMethod.isAccessible = true
            attachMethod.invoke(app, base)

            originalApp = app
            Timber.d("Original Application attached: %s", originalAppClassName)
        } catch (e: Exception) {
            Timber.e(e, "Failed to attach original Application: %s", originalAppClassName)
        }
    }

    override fun onCreate() {
        super.onCreate()
        originalApp?.onCreate()
    }

    override fun onTerminate() {
        super.onTerminate()
        originalApp?.onTerminate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        originalApp?.onConfigurationChanged(newConfig)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        originalApp?.onLowMemory()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        originalApp?.onTrimMemory(level)
    }

    private fun isDebugBuild(): Boolean {
        return try {
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Exception) {
            false
        }
    }
}
