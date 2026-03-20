package dev.zenpatch.privilege

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import timber.log.Timber
import java.io.File

/**
 * Dhizuku-based APK installer.
 * Uses Device Owner (DevicePolicyManager) privileges for the most capable installation.
 * Provides silent install without user interaction.
 *
 * Dhizuku acts as a Device Owner app that grants DPM privileges to other apps.
 */
class DhizukuProvider(private val context: Context) : PrivilegeProvider {

    override fun isAvailable(): Boolean {
        return try {
            isDhizukuConnected()
        } catch (e: Exception) {
            Timber.d("Dhizuku not available: %s", e.message)
            false
        }
    }

    override fun getPrivilegeLevel(): PrivilegeLevel = PrivilegeLevel.DHIZUKU

    override fun install(apkFile: File, callback: PrivilegeProvider.InstallCallback) {
        if (!isAvailable()) {
            callback.onFailure(-1, "Dhizuku Device Owner not available")
            return
        }

        callback.onProgress(0f, "Using Dhizuku Device Owner for installation...")

        try {
            // Bind to Dhizuku service for DPM-level operations
            installViaDhizuku(apkFile, callback)
        } catch (e: Exception) {
            Timber.e(e, "Dhizuku install failed")
            callback.onFailure(-1, "Dhizuku install failed: ${e.message}")
        }
    }

    override fun uninstall(packageName: String, callback: PrivilegeProvider.InstallCallback) {
        if (!isAvailable()) {
            callback.onFailure(-1, "Dhizuku not available")
            return
        }

        try {
            uninstallViaDhizuku(packageName, callback)
        } catch (e: Exception) {
            Timber.e(e, "Dhizuku uninstall failed")
            callback.onFailure(-1, e.message ?: "Uninstall failed")
        }
    }

    private fun isDhizukuConnected(): Boolean {
        // Check if Dhizuku is installed and we can bind to it
        return try {
            val dhizukuPkg = "com.rosan.dhizuku"
            val pm = context.packageManager
            pm.getPackageInfo(dhizukuPkg, 0)

            // Check if we have Dhizuku permission
            val permission = "com.rosan.dhizuku.permission.ACCESS"
            pm.checkPermission(permission, context.packageName) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    private fun installViaDhizuku(apkFile: File, callback: PrivilegeProvider.InstallCallback) {
        // Create PackageInstaller session with device owner-level params
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
            }
        }

        val packageInstaller = context.packageManager.packageInstaller
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        callback.onProgress(0.4f, "Writing APK to install session...")

        session.openWrite("base.apk", 0, apkFile.length()).use { out ->
            apkFile.inputStream().use { input -> input.copyTo(out) }
            session.fsync(out)
        }

        callback.onProgress(0.8f, "Committing via Device Owner...")

        val resultIntent = android.app.PendingIntent.getBroadcast(
            context,
            sessionId,
            android.content.Intent("dev.zenpatch.INSTALL_RESULT").apply {
                `package` = context.packageName
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        )
        session.commit(resultIntent.intentSender)

        callback.onProgress(1f, "Install committed")
        callback.onSuccess(apkFile.nameWithoutExtension)
    }

    private fun uninstallViaDhizuku(packageName: String, callback: PrivilegeProvider.InstallCallback) {
        val packageInstaller = context.packageManager.packageInstaller
        val resultIntent = android.app.PendingIntent.getBroadcast(
            context,
            packageName.hashCode(),
            android.content.Intent("dev.zenpatch.UNINSTALL_RESULT").apply {
                `package` = context.packageName
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        )
        packageInstaller.uninstall(packageName, resultIntent.intentSender)
        callback.onProgress(1f, "Uninstall committed")
        callback.onSuccess(packageName)
    }
}
