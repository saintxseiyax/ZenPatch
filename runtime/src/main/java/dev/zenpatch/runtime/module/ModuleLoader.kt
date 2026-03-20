// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.runtime.module

import android.content.Context
import android.net.Uri
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.zip.ZipFile

/**
 * Discovers, loads, and invokes Xposed modules embedded within or alongside the patched APK.
 *
 * Module discovery order:
 *  1. Modules embedded as assets inside the patched APK (`assets/zenpatch/modules/`)
 *  2. Modules provided via [dev.zenpatch.runtime.ipc.ConfigProvider] (from ZenPatch Manager)
 *
 * Each module runs in its own [ModuleClassLoader] to ensure class isolation.
 * A module crash is caught and logged; it does not bring down the host app.
 */
object ModuleLoader {

    private const val TAG = "ZP_ModuleLoader"
    private const val EMBEDDED_MODULES_ASSET_DIR = "zenpatch/modules"
    private const val MODULES_SUBDIR = "zenpatch/modules"

    /** All successfully loaded and enabled module configurations. */
    private val loadedModules = mutableListOf<LoadedModule>()

    /**
     * Discover and load all configured modules for the current app context.
     *
     * @param context Application context of the patched app.
     */
    fun loadModules(context: Context) {
        Log.d(TAG, "Discovering modules for ${context.packageName}")

        val configs = mutableListOf<ModuleConfig>()

        // --- 1a. Embedded modules from assets/zenpatch/modules/ ---
        configs += discoverEmbeddedModules(context)

        // --- 1b. External modules via ConfigProvider (Phase 2 stub) ---
        configs += discoverExternalModules(context)

        Log.d(TAG, "Found ${configs.size} module(s) total")

        // --- 2+3. Parse and load each module ---
        for (config in configs) {
            if (!config.isEnabled) {
                Log.d(TAG, "Skipping disabled module: ${config.packageName}")
                continue
            }
            loadModule(context, config)
        }

        // --- 4. Notify all loaded modules about the current package ---
        val processName = try {
            android.app.Application.getProcessName()
        } catch (e: Throwable) {
            context.packageName
        }

        val lpparam = XC_LoadPackage.LoadPackageParam(
            context.packageName,
            processName,
            context.classLoader,
            true
        )
        notifyPackageLoaded(lpparam)

        Log.i(TAG, "ModuleLoader finished: ${loadedModules.size} module hook(s) active")
    }

    // ---- Private helpers ----------------------------------------------------------------

    /**
     * Extract module APKs from `assets/zenpatch/modules/` into the app's private files dir
     * and return a [ModuleConfig] for each one.
     */
    private fun discoverEmbeddedModules(context: Context): List<ModuleConfig> {
        val result = mutableListOf<ModuleConfig>()
        val assetManager = context.assets
        val assetFiles = try {
            assetManager.list(EMBEDDED_MODULES_ASSET_DIR) ?: emptyArray()
        } catch (e: Exception) {
            Log.d(TAG, "No embedded modules asset dir found (${EMBEDDED_MODULES_ASSET_DIR}): ${e.message}")
            return result
        }

        // Destination directory inside the app's private storage
        val modulesDir = File(context.filesDir, MODULES_SUBDIR).also { it.mkdirs() }

        for (fileName in assetFiles) {
            if (!fileName.endsWith(".apk")) continue
            try {
                val destFile = File(modulesDir, fileName)
                // Copy asset APK to private storage (required to use as classpath)
                assetManager.open("$EMBEDDED_MODULES_ASSET_DIR/$fileName").use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                Log.d(TAG, "Extracted embedded module APK: ${destFile.absolutePath}")

                val config = parseModuleApk(context, destFile.absolutePath)
                if (config != null) {
                    result += config
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract/parse embedded module $fileName", e)
            }
        }
        return result
    }

    /**
     * Query the ZenPatch Manager ContentProvider for externally-configured module APKs.
     * This is a Phase-2 stub: failures fall back gracefully to an empty list.
     */
    private fun discoverExternalModules(context: Context): List<ModuleConfig> {
        val result = mutableListOf<ModuleConfig>()
        try {
            val authority = "${context.packageName}.zenpatch.config"
            val uri = Uri.parse("content://$authority/modules")
            val cursor = context.contentResolver.query(uri, null, null, null, null)
                ?: return result
            cursor.use { c ->
                while (c.moveToNext()) {
                    try {
                        val apkPathRaw = c.getString(c.getColumnIndexOrThrow("apk_path"))
                        // Validate the path is within the app's private files directory to
                        // prevent a compromised or malicious provider from pointing the loader
                        // at an attacker-controlled APK outside our sandbox.
                        val apkFile = java.io.File(apkPathRaw).canonicalFile
                        val allowedRoot = context.filesDir.canonicalFile
                        if (!apkFile.path.startsWith(allowedRoot.path + java.io.File.separator) &&
                            apkFile.path != allowedRoot.path) {
                            Log.w(TAG, "Rejecting external module path outside app files dir: $apkPathRaw")
                            continue
                        }
                        val apkPath = apkFile.absolutePath
                        val packageName = c.getString(c.getColumnIndexOrThrow("package_name"))
                        val entryPointsRaw = c.getString(c.getColumnIndexOrThrow("entry_points"))
                        val minVersion = runCatching { c.getInt(c.getColumnIndexOrThrow("min_version")) }.getOrDefault(1)
                        val description = runCatching { c.getString(c.getColumnIndexOrThrow("description")) }.getOrDefault("")
                        val isEnabled = runCatching { c.getInt(c.getColumnIndexOrThrow("is_enabled")) != 0 }.getOrDefault(true)
                        val entryPoints = entryPointsRaw
                            .split("\n")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        result += ModuleConfig(
                            packageName = packageName,
                            apkPath = apkPath,
                            entryPoints = entryPoints,
                            minVersion = minVersion,
                            description = description,
                            isEnabled = isEnabled
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse external module row", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "External module discovery not available (Phase 2): ${e.message}")
        }
        return result
    }

    /**
     * Parse a module APK to produce a [ModuleConfig].
     *
     * Reads `assets/xposed_init` for entry-point class names and derives the
     * package name via [android.content.pm.PackageManager].
     *
     * @return A [ModuleConfig], or null if the APK is not a valid Xposed module.
     */
    private fun parseModuleApk(context: Context, apkPath: String): ModuleConfig? {
        // Read entry points from xposed_init inside the APK zip
        val entryPoints: List<String> = try {
            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry("assets/xposed_init")
                    ?: run {
                        Log.w(TAG, "No assets/xposed_init in $apkPath — not an Xposed module")
                        return null
                    }
                zip.getInputStream(entry).bufferedReader().readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read xposed_init from $apkPath", e)
            return null
        }

        if (entryPoints.isEmpty()) {
            Log.w(TAG, "xposed_init in $apkPath has no entry points")
            return null
        }

        // Derive package name via PackageManager (parses AndroidManifest.xml)
        val packageName: String = try {
            val pkgInfo = context.packageManager.getPackageArchiveInfo(apkPath, 0)
            pkgInfo?.packageName ?: run {
                Log.w(TAG, "Could not parse package name from $apkPath")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "PackageManager failed to parse $apkPath", e)
            return null
        }

        return ModuleConfig(
            packageName = packageName,
            apkPath = apkPath,
            entryPoints = entryPoints,
            minVersion = 1,
            description = "",
            isEnabled = true
        )
    }

    /**
     * Load a single module: create its [ModuleClassLoader], instantiate every
     * [IXposedHookLoadPackage] entry point, and register the resulting [LoadedModule]s.
     */
    private fun loadModule(context: Context, config: ModuleConfig) {
        Log.d(TAG, "Loading module ${config.packageName} from ${config.apkPath}")
        val classLoader = try {
            ModuleClassLoader.create(config, context.classLoader)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ModuleClassLoader for ${config.packageName}", e)
            return
        }

        for (entryPoint in config.entryPoints) {
            try {
                val clazz = classLoader.loadClass(entryPoint)
                if (!IXposedHookLoadPackage::class.java.isAssignableFrom(clazz)) {
                    Log.d(TAG, "Entry point $entryPoint does not implement IXposedHookLoadPackage, skipping")
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                val instance = clazz.getDeclaredConstructor().newInstance() as IXposedHookLoadPackage
                loadedModules += LoadedModule(
                    config = config,
                    classLoader = classLoader,
                    hook = instance
                )
                Log.i(TAG, "Loaded module entry point: $entryPoint (${config.packageName})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load entry point $entryPoint from ${config.packageName}", e)
            }
        }
    }

    /**
     * Invoke `handleLoadPackage` on all loaded modules.
     *
     * @param lpparam The [XC_LoadPackage.LoadPackageParam] for the current package.
     */
    fun notifyPackageLoaded(lpparam: XC_LoadPackage.LoadPackageParam) {
        for (module in loadedModules) {
            try {
                module.hook.handleLoadPackage(lpparam)
            } catch (e: Exception) {
                Log.e(TAG, "Module ${module.config.packageName} crashed in handleLoadPackage", e)
            }
        }
    }

    /** Container for a successfully loaded module. */
    private data class LoadedModule(
        val config: ModuleConfig,
        val classLoader: ModuleClassLoader,
        val hook: IXposedHookLoadPackage
    )
}
