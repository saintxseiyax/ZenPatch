package dev.zenpatch.manager

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import timber.log.Timber
import timber.log.Timber.DebugTree

/**
 * Application class for ZenPatch Manager.
 * Initializes Timber logging and notification channels.
 */
class ZenPatchApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber
        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTree())
        } else {
            Timber.plant(CrashReportingTree())
        }

        Timber.d("ZenPatch Manager starting up (version %s)", BuildConfig.VERSION_NAME)

        // Create notification channels
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            val patchingChannel = NotificationChannel(
                CHANNEL_PATCHING_ID,
                getString(R.string.channel_patching_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of APK patching operations"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(patchingChannel)
        }
    }

    companion object {
        const val CHANNEL_PATCHING_ID = "patching"
    }

    /**
     * Timber tree for release builds.
     * Only logs WARN and above, and forwards errors to crash reporting.
     */
    private class CrashReportingTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority < android.util.Log.WARN) return

            // Log to Android's log system for WARN+
            android.util.Log.println(priority, tag ?: "ZenPatch", message)

            // Forward errors to crash tracking (no-op in this implementation,
            // but ready for Firebase Crashlytics or similar)
            if (priority == android.util.Log.ERROR && t != null) {
                // CrashTracker.recordException(t)
            }
        }
    }
}
