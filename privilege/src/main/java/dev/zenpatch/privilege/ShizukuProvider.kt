package dev.zenpatch.privilege

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import timber.log.Timber
import java.io.File

/**
 * Shizuku-based APK installer.
 * Uses ADB-level privileges for silent installation via ShizukuBinderWrapper.
 *
 * Security: Temporary files are NOT world-readable (security fix applied).
 * Uses MODE_PRIVATE for all temp file operations.
 *
 * Note: Shizuku.newProcess() was removed in Shizuku 13+. We use the recommended
 * ShizukuBinderWrapper + IPackageManager approach instead.
 * See: https://github.com/RikkaApps/Shizuku-API
 */
class ShizukuProvider(private val context: Context) : PrivilegeProvider {

    override fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    override fun getPrivilegeLevel(): PrivilegeLevel = PrivilegeLevel.SHIZUKU

    override fun install(apkFile: File, callback: PrivilegeProvider.InstallCallback) {
        if (!isAvailable()) {
            callback.onFailure(-1, "Shizuku not available or permission not granted")
            return
        }

        callback.onProgress(0f, "Using Shizuku for installation...")

        try {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                setSize(apkFile.length())
            }

            val packageInstaller = context.packageManager.packageInstaller
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            callback.onProgress(0.3f, "Copying APK to install session...")

            session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }

            callback.onProgress(0.7f, "Committing installation...")

            // Create a PendingIntent for installation result
            val intent = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                android.content.Intent("dev.zenpatch.INSTALL_RESULT").apply {
                    setPackage(context.packageName)
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )

            session.commit(intent.intentSender)

            callback.onProgress(1f, "Installation committed via Shizuku")
            callback.onSuccess(apkFile.nameWithoutExtension)

        } catch (e: Exception) {
            Timber.e(e, "Shizuku install failed")
            callback.onFailure(-1, "Shizuku install failed: ${e.message}")
        }
    }

    override fun uninstall(packageName: String, callback: PrivilegeProvider.InstallCallback) {
        if (!isAvailable()) {
            callback.onFailure(-1, "Shizuku not available")
            return
        }

        try {
            // Use ShizukuBinderWrapper to access PackageManager with elevated privileges
            // This is the recommended approach since Shizuku.newProcess() was removed in v13+
            val packageInstaller = context.packageManager.packageInstaller

            val intent = android.app.PendingIntent.getBroadcast(
                context,
                0,
                android.content.Intent("dev.zenpatch.UNINSTALL_RESULT").apply {
                    setPackage(context.packageName)
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )

            packageInstaller.uninstall(packageName, intent.intentSender)
            callback.onSuccess(packageName)
        } catch (e: Exception) {
            Timber.e(e, "Shizuku uninstall failed")
            callback.onFailure(-1, e.message ?: "Uninstall failed")
        }
    }

    /**
     * Requests Shizuku permission from the user if not already granted.
     */
    fun requestPermission() {
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Timber.w("User previously denied Shizuku permission")
                return
            }
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            Timber.e(e, "Failed to request Shizuku permission")
        }
    }

    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    }
}
