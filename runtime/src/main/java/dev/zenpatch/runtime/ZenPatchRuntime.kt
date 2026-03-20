// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.runtime

import android.content.Context
import android.util.Log
import de.robv.android.xposed.XposedBridge
import dev.zenpatch.runtime.hook.SignatureSpoof
import dev.zenpatch.runtime.hook.XposedBridgeImpl
import dev.zenpatch.runtime.module.ModuleLoader

/**
 * Main runtime initialiser – entry point when the patched app boots.
 *
 * Called from [ZenPatchAppProxy.attachBaseContext] before the original
 * Application has been instantiated. Runs the following bootstrap sequence:
 *
 *  1. Load native bridge (LSPlant init)
 *  2. Inject XposedBridge implementation
 *  3. Activate hidden-API bypass
 *  4. Install signature spoofing hook
 *  5. Discover and load Xposed modules
 */
object ZenPatchRuntime {

    private const val TAG = "ZenPatchRuntime"

    /**
     * Initialise the runtime in the context of the patched app.
     *
     * @param context Application context of the patched app.
     * @param originalApplicationClass Fully-qualified class name of the original Application,
     *   or null if the manifest did not specify one.
     */
    fun init(context: Context, originalApplicationClass: String?) {
        Log.i(TAG, "Initialising ZenPatch runtime")

        try {
            // Step 1: Initialise native bridge + LSPlant
            val nativeReady = NativeBridge.init()
            if (!nativeReady) {
                Log.w(TAG, "Native bridge init failed – running without hooks")
                return
            }

            // Step 2: Inject the XposedBridge implementation
            XposedBridge.setImpl(XposedBridgeImpl())
            Log.i(TAG, "XposedBridge implementation registered")

            // Step 3: Hidden API bypass
            HiddenApiBypass.install()

            // Step 4: Install signature spoofing
            SignatureSpoof.install(context)

            // Step 5: Load modules
            ModuleLoader.loadModules(context)

            Log.i(TAG, "ZenPatch runtime initialised successfully")
        } catch (e: Exception) {
            // Graceful degradation: log but don't crash the host app
            Log.e(TAG, "ZenPatch runtime init failed – running without hooks", e)
        }
    }
}
