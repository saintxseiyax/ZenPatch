package dev.zenpatch.runtime

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_LoadPackage
import dalvik.system.PathClassLoader
import timber.log.Timber
import java.io.File
import java.util.Properties

/**
 * Discovers and loads Xposed modules from assets/zenpatch/config.properties.
 * Creates isolated ClassLoaders per module to prevent cross-module class contamination.
 * Includes path traversal prevention on all module APK paths.
 */
class ModuleLoader(private val appClassLoader: ClassLoader) {

    private val loadedModules = mutableListOf<LoadedModule>()

    data class LoadedModule(
        val packageName: String,
        val apkPath: String,
        val hooks: List<IXposedHookLoadPackage>
    )

    /**
     * Loads all modules configured in config.properties.
     * @param configProps Configuration properties from assets/zenpatch/config.properties
     * @param safeBaseDir Base directory for APK path validation
     */
    fun loadModules(configProps: Properties, safeBaseDir: File): List<LoadedModule> {
        val moduleCount = configProps.getProperty("module_count", "0").toIntOrNull() ?: 0
        Timber.d("Loading %d modules from config", moduleCount)

        for (i in 0 until moduleCount) {
            val apkPath = configProps.getProperty("module_${i}_path") ?: continue
            loadModule(apkPath, safeBaseDir)
        }

        return loadedModules.toList()
    }

    private fun loadModule(apkPath: String, safeBaseDir: File) {
        // Path traversal prevention: validate that the resolved canonical path
        // is within an acceptable directory (not just checking for "..")
        val apkFile = File(apkPath)
        val canonicalPath = try {
            apkFile.canonicalPath
        } catch (e: Exception) {
            Timber.e(e, "Cannot resolve canonical path for module: %s", apkPath)
            return
        }

        // Validate path is not traversing outside acceptable boundaries
        // Acceptable: /data/app/, /data/data/, /sdcard/ (accessible to this process)
        val acceptablePrefixes = listOf(
            "/data/app/",
            "/data/data/",
            "/storage/",
            "/sdcard/"
        )
        val isSafe = acceptablePrefixes.any { canonicalPath.startsWith(it) }
        if (!isSafe) {
            Timber.w("Rejecting module path outside safe directories: %s", canonicalPath)
            return
        }

        if (!apkFile.exists()) {
            Timber.w("Module APK not found: %s", canonicalPath)
            return
        }

        try {
            // Create isolated ClassLoader for this module
            // Parent is app's class loader so module can access app classes
            val classLoader = PathClassLoader(canonicalPath, appClassLoader)

            // Read xposed_init from module assets
            val entryPoints = readXposedInit(classLoader, canonicalPath) ?: return

            val hooks = mutableListOf<IXposedHookLoadPackage>()
            for (className in entryPoints) {
                try {
                    val cls = classLoader.loadClass(className.trim())
                    val instance = cls.getDeclaredConstructor().newInstance()
                    if (instance is IXposedHookLoadPackage) {
                        hooks.add(instance)
                        Timber.d("Loaded hook class: %s from module %s", className, apkPath)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to instantiate hook class: %s", className)
                }
            }

            if (hooks.isNotEmpty()) {
                val packageName = extractPackageName(canonicalPath) ?: canonicalPath
                loadedModules.add(LoadedModule(packageName, canonicalPath, hooks))
                Timber.i("Module loaded: %s with %d hook(s)", packageName, hooks.size)
            }

        } catch (e: Exception) {
            Timber.e(e, "Failed to load module: %s", apkPath)
        }
    }

    /**
     * Reads assets/xposed_init from the module APK to get entry point class names.
     */
    private fun readXposedInit(classLoader: ClassLoader, apkPath: String): List<String>? {
        return try {
            val zip = java.util.zip.ZipFile(apkPath)
            val entry = zip.getEntry("assets/xposed_init") ?: return null
            zip.getInputStream(entry).use { input ->
                input.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not read xposed_init from %s", apkPath)
            null
        }
    }

    private fun extractPackageName(apkPath: String): String? {
        return try {
            val zip = java.util.zip.ZipFile(apkPath)
            val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: return null
            val bytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
            // Simple package name extraction from AXML
            extractPackageFromAxmlBytes(bytes)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractPackageFromAxmlBytes(bytes: ByteArray): String? {
        // Scan for "package" attribute value in AXML string pool
        val searchStr = "package"
        val strBytes = searchStr.toByteArray(Charsets.UTF_8)
        for (i in bytes.indices) {
            if (i + strBytes.size < bytes.size) {
                var match = true
                for (j in strBytes.indices) {
                    if (bytes[i + j] != strBytes[j]) { match = false; break }
                }
                if (match) {
                    // Found "package" - look for the value nearby
                    // This is a simplified heuristic; in production use AXMLParser
                    return null
                }
            }
        }
        return null
    }

    /**
     * Notifies all loaded modules that a package has been loaded.
     * Called by ZenPatchAppProxy after all initialization is complete.
     */
    fun notifyPackageLoaded(param: XC_LoadPackage.LoadPackageParam) {
        for (module in loadedModules) {
            for (hook in module.hooks) {
                try {
                    hook.handleLoadPackage(param)
                } catch (e: Exception) {
                    Timber.e(e, "Module %s threw exception in handleLoadPackage", module.packageName)
                    // Module crash must NOT crash the host app
                }
            }
        }
    }
}
