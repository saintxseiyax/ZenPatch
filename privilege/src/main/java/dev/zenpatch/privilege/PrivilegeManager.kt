package dev.zenpatch.privilege

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * Selects the best available privilege provider automatically.
 * Priority: Dhizuku → Shizuku → Standard
 */
class PrivilegeManager(private val context: Context) {

    private val providers: List<PrivilegeProvider> by lazy {
        listOf(
            DhizukuProvider(context),
            ShizukuProvider(context),
            StandardInstaller(context)
        )
    }

    /**
     * Returns the best available privilege provider.
     */
    fun getBestProvider(): PrivilegeProvider {
        val best = providers.firstOrNull { it.isAvailable() } ?: StandardInstaller(context)
        Timber.d("Selected privilege provider: %s (level=%s)",
            best.javaClass.simpleName, best.getPrivilegeLevel().displayName)
        return best
    }

    /**
     * Returns the provider for a specific preference string.
     * @param preference "auto" | "dhizuku" | "shizuku" | "standard"
     */
    fun getProvider(preference: String): PrivilegeProvider {
        val provider = when (preference.lowercase()) {
            "dhizuku" -> providers.filterIsInstance<DhizukuProvider>().firstOrNull()
            "shizuku" -> providers.filterIsInstance<ShizukuProvider>().firstOrNull()
            "standard" -> providers.filterIsInstance<StandardInstaller>().firstOrNull()
            else -> null // "auto" -> fall through to getBestProvider
        }

        return if (provider?.isAvailable() == true) {
            Timber.d("Using preferred provider: %s", preference)
            provider
        } else {
            if (preference != "auto") {
                Timber.w("Preferred provider '%s' not available, falling back to auto", preference)
            }
            getBestProvider()
        }
    }

    /**
     * Gets all available providers and their status.
     */
    fun getAvailabilityReport(): List<Pair<PrivilegeProvider, Boolean>> {
        return providers.map { it to it.isAvailable() }
    }

    /**
     * Installs an APK using the best available provider.
     */
    fun install(
        apkFile: File,
        preferredProvider: String = "auto",
        callback: PrivilegeProvider.InstallCallback
    ) {
        val provider = getProvider(preferredProvider)
        Timber.i("Installing %s via %s", apkFile.name, provider.javaClass.simpleName)
        provider.install(apkFile, callback)
    }

    /**
     * Uninstalls a package using the best available provider.
     */
    fun uninstall(
        packageName: String,
        preferredProvider: String = "auto",
        callback: PrivilegeProvider.InstallCallback
    ) {
        val provider = getProvider(preferredProvider)
        Timber.i("Uninstalling %s via %s", packageName, provider.javaClass.simpleName)
        provider.uninstall(packageName, callback)
    }
}
