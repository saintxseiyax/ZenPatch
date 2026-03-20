package dev.zenpatch.privilege

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File

/**
 * Standard APK installer using PackageInstaller API.
 * Requires user interaction (system install dialog).
 * Available on all Android versions.
 */
class StandardInstaller(private val context: Context) : PrivilegeProvider {

    override fun isAvailable(): Boolean = true

    override fun getPrivilegeLevel(): PrivilegeLevel = PrivilegeLevel.STANDARD

    override fun install(apkFile: File, callback: PrivilegeProvider.InstallCallback) {
        if (!apkFile.exists() || !apkFile.canRead()) {
            callback.onFailure(-1, "APK file not readable: ${apkFile.absolutePath}")
            return
        }

        callback.onProgress(0f, "Preparing installation...")

        try {
            // Use PackageInstaller session API for better error handling
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(null)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            callback.onProgress(0.3f, "Copying APK...")

            session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                apkFile.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }

            callback.onProgress(0.7f, "Starting install session...")

            // For standard install, launch the system installer UI
            val uri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            } catch (e: Exception) {
                Timber.w(e, "FileProvider not available, using direct URI")
                Uri.fromFile(apkFile)
            }

            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }

            context.startActivity(installIntent)
            session.abandon()

            callback.onProgress(1f, "Installation started")
            callback.onSuccess(apkFile.nameWithoutExtension)

        } catch (e: Exception) {
            Timber.e(e, "Standard install failed")
            callback.onFailure(-1, e.message ?: "Installation failed")
        }
    }

    override fun uninstall(packageName: String, callback: PrivilegeProvider.InstallCallback) {
        val uri = Uri.parse("package:$packageName")
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        try {
            context.startActivity(intent)
            callback.onSuccess(packageName)
        } catch (e: Exception) {
            callback.onFailure(-1, e.message ?: "Uninstall failed")
        }
    }
}
