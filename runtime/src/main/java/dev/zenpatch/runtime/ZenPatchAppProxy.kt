package dev.zenpatch.runtime

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import de.robv.android.xposed.XC_LoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedBridgeImpl
import timber.log.Timber
import timber.log.Timber.DebugTree
import java.io.File
import java.util.Properties

/**
 * Application proxy injected into patched APKs as the new Application class.
 * Bootstraps ZenPatch runtime before delegating to the original Application.
 *
 * Boot sequence in attachBaseContext():
 *   1. Init Timber logging
 *   2. Load native bridge (LSPlant)
 *   3. Install Hidden API bypass
 *   4. Set XposedBridge implementation
 *   5. Install SignatureSpoof hooks
 *   6. Load modules
 *   7. Forward to original Application class
 */
class ZenPatchAppProxy : Application() {

    private var originalApp: Application? = null
    private var moduleLoader: ModuleLoader? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        // Initialize logging
        if (isDebugBuild()) Timber.plant(DebugTree())

        Timber.d("ZenPatchAppProxy.attachBaseContext starting…")

        try {
            bootstrapRuntime(base)
        } catch (e: Exception) {
            // Graceful degradation: if bootstrap fails, still start the original app
            Timber.e(e, "ZenPatch bootstrap failed, starting original app without hooks")
        }

        // Delegate to original application
        createAndAttachOriginalApp(base)
    }

    private fun bootstrapRuntime(context: Context) {
        // Step 1: Read config
        val config = readConfig(context)
        val packageName = config.getProperty("package_name", context.packageName)
        val originalAppClass = config.getProperty("original_application")

        Timber.d("Config: package=%s, originalApp=%s", packageName, originalAppClass)

        // Step 2: Init native bridge (LSPlant)
        val nativeReady = NativeBridge.init()
        if (!nativeReady) {
            Timber.w("Native bridge init failed - hooks will not work")
            return
        }

        // Step 3: Hidden API bypass
        HiddenApiBypass.install()

        // Step 4: Set XposedBridge implementation (Provider-Injection-Pattern)
        XposedBridge.setImpl(NativeBridgeXposedImpl())
        Timber.d("XposedBridge implementation set")

        // Step 5: Signature spoofing
        val signatureSpoof = SignatureSpoof.fromAssets(packageName, classLoader)
        signatureSpoof.install()

        // Step 6: Load modules
        val loader = ModuleLoader(classLoader)
        val baseDir = context.filesDir ?: File("/data/data/$packageName")
        loader.loadModules(config, baseDir)
        moduleLoader = loader

        // Step 7: Notify modules that package is loaded
        val loadPackageParam = XC_LoadPackage.LoadPackageParam(
            packageName = packageName,
            processName = context.packageName,
            classLoader = classLoader,
            appInfo = context.applicationInfo,
            isFirstApplication = true
        )
        loader.notifyPackageLoaded(loadPackageParam)

        Timber.i("ZenPatch runtime bootstrap complete for %s", packageName)
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

/**
 * XposedBridge implementation backed by NativeBridge (LSPlant).
 * Follows the Provider-Injection-Pattern: XposedBridge delegates to this impl,
 * avoiding circular dependencies between xposed-api and runtime modules.
 */
internal class NativeBridgeXposedImpl : XposedBridgeImpl {

    // Maintains handle -> unhook mapping
    private val hookHandles = mutableMapOf<de.robv.android.xposed.XC_MethodHook.Unhook, Long>()

    override fun hookMethod(
        method: java.lang.reflect.Member,
        callback: de.robv.android.xposed.XC_MethodHook
    ): de.robv.android.xposed.XC_MethodHook.Unhook {
        val hookMethod = buildHookMethod(method, callback)
        val handle = NativeBridge.hookMethod(method, hookMethod, null)
        val unhook = de.robv.android.xposed.XC_MethodHook.Unhook(callback, method)
        hookHandles[unhook] = handle
        return unhook
    }

    override fun unhookMethod(unhook: de.robv.android.xposed.XC_MethodHook.Unhook) {
        val handle = hookHandles.remove(unhook) ?: return
        NativeBridge.unhookMethod(handle)
    }

    override fun invokeOriginalMethod(
        method: java.lang.reflect.Member,
        thisObject: Any?,
        args: Array<Any?>?
    ): Any? {
        val unhook = hookHandles.keys.firstOrNull {
            it.hookedMethod == method
        } ?: run {
            // Method not hooked, invoke directly
            return when (method) {
                is java.lang.reflect.Method -> method.invoke(thisObject, *(args ?: emptyArray()))
                is java.lang.reflect.Constructor<*> -> method.newInstance(*(args ?: emptyArray()))
                else -> null
            }
        }
        val handle = hookHandles[unhook] ?: return null
        return NativeBridge.invokeOriginal(handle, thisObject, args)
    }

    private fun buildHookMethod(
        target: java.lang.reflect.Member,
        callback: de.robv.android.xposed.XC_MethodHook
    ): java.lang.reflect.Method {
        // In a real implementation, this generates a synthetic Method that
        // calls callback.beforeHookedMethod/afterHookedMethod via the bridge.
        // This is the core mechanism of Xposed hooking.
        // For compilation purposes, we return a reflective reference.
        // The native bridge handles the actual dispatch via LSPlant::Hook.
        val dispatchClass = HookDispatcher::class.java
        return dispatchClass.getDeclaredMethod("dispatch", Any::class.java, Array<Any?>::class.java)
    }
}

/**
 * Static dispatcher invoked by the native bridge for each hooked method call.
 */
@Suppress("unused")
object HookDispatcher {

    @JvmStatic
    fun dispatch(thisObject: Any?, args: Array<Any?>?): Any? {
        // This method is called by the native bridge when a hooked method is invoked.
        // The native code passes the hook context; actual before/after dispatch
        // is handled by the LSPlant callback mechanism.
        return null
    }
}
