package dev.zenpatch.privilege

import java.io.File

/**
 * Interface for APK installation privilege providers.
 * Different implementations offer different privilege levels.
 */
interface PrivilegeProvider {

    /**
     * Checks if this privilege provider is currently available.
     */
    fun isAvailable(): Boolean

    /**
     * Returns the privilege level (higher = more capabilities).
     */
    fun getPrivilegeLevel(): PrivilegeLevel

    /**
     * Installs an APK.
     * @param apkFile The APK to install
     * @param callback Called with progress/result
     */
    fun install(apkFile: File, callback: InstallCallback)

    /**
     * Uninstalls a package.
     * @param packageName The package to uninstall
     * @param callback Called with result
     */
    fun uninstall(packageName: String, callback: InstallCallback)

    interface InstallCallback {
        fun onProgress(progress: Float, message: String)
        fun onSuccess(packageName: String)
        fun onFailure(errorCode: Int, errorMessage: String)
    }
}

enum class PrivilegeLevel(val level: Int, val displayName: String) {
    STANDARD(0, "Standard (Package Installer)"),
    SHIZUKU(1, "Shizuku (ADB)"),
    DHIZUKU(2, "Dhizuku (Device Owner)"),
    ROOT(3, "Root")
}
