package dev.zenpatch.manager.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Repository for discovering and managing Xposed modules.
 * Discovers modules both from installed packages (xposedmodule meta-data)
 * and from local APK files.
 */
class ModulesRepository(private val context: Context) {

    private val _modules = MutableStateFlow<List<InstalledModule>>(emptyList())
    val modules: Flow<List<InstalledModule>> = _modules.asStateFlow()

    suspend fun refreshModules() = withContext(Dispatchers.IO) {
        val discovered = discoverInstalledModules()
        _modules.value = discovered
    }

    private fun discoverInstalledModules(): List<InstalledModule> {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(
                PackageManager.GET_META_DATA.toLong()
            ))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
        }

        return packages.mapNotNull { packageInfo ->
            val appInfo = packageInfo.applicationInfo ?: return@mapNotNull null
            val metaData = appInfo.metaData ?: return@mapNotNull null

            // Check for xposedmodule marker
            if (!metaData.containsKey("xposedmodule")) return@mapNotNull null

            val description = metaData.getString("xposeddescription", "")
            val minVersion = metaData.getInt("xposedminversion", 1)
            val versionName = packageInfo.versionName ?: "Unknown"
            val appName = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageInfo.packageName
            }

            Timber.d("Discovered Xposed module: %s", packageInfo.packageName)

            InstalledModule(
                packageName = packageInfo.packageName,
                name = appName,
                description = description ?: "",
                version = versionName,
                minXposedVersion = minVersion,
                apkPath = appInfo.sourceDir,
                isEnabled = true,
                targetPackages = emptySet()
            )
        }
    }

    suspend fun toggleModule(packageName: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val updated = _modules.value.map { module ->
            if (module.packageName == packageName) module.copy(isEnabled = enabled) else module
        }
        _modules.value = updated
    }

    fun getModule(packageName: String): InstalledModule? {
        return _modules.value.firstOrNull { it.packageName == packageName }
    }

    fun getEnabledModulesForPackage(targetPackage: String): List<InstalledModule> {
        return _modules.value.filter { module ->
            module.isEnabled && (module.targetPackages.isEmpty() || targetPackage in module.targetPackages)
        }
    }
}
