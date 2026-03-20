package dev.zenpatch.runtime

import timber.log.Timber

/**
 * JNI bridge to the native zenpatch_bridge library (LSPlant integration).
 * Loaded by ZenPatchAppProxy.attachBaseContext() before any hooks are installed.
 */
object NativeBridge {

    private var isLoaded = false
    private var isInitialized = false

    /**
     * Loads libzenpatch_bridge.so and initializes LSPlant.
     * Must be called before any hook operations.
     * @return true if initialization succeeded
     */
    fun init(): Boolean {
        if (isInitialized) return true
        return try {
            System.loadLibrary("zenpatch_bridge")
            isLoaded = true
            val result = nativeInit()
            isInitialized = result
            Timber.d("NativeBridge.init() = %b", result)
            result
        } catch (e: UnsatisfiedLinkError) {
            Timber.e(e, "Failed to load zenpatch_bridge native library")
            false
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize NativeBridge")
            false
        }
    }

    fun isReady(): Boolean = isInitialized

    /**
     * Hooks a method using LSPlant.
     * @param targetMethod The method to hook (java.lang.reflect.Method or Constructor)
     * @param hookMethod The replacement method
     * @param callbackObject The object owning the hook method (or null for static)
     * @return Handle for the hook (used for unhooking and calling original)
     */
    fun hookMethod(
        targetMethod: java.lang.reflect.Member,
        hookMethod: java.lang.reflect.Method,
        callbackObject: Any?
    ): Long {
        check(isInitialized) { "NativeBridge not initialized" }
        return nativeHookMethod(targetMethod, hookMethod, callbackObject)
    }

    /**
     * Unhooks a previously hooked method.
     * @param hookHandle Handle returned by hookMethod
     */
    fun unhookMethod(hookHandle: Long) {
        if (!isInitialized || hookHandle == 0L) return
        nativeUnhookMethod(hookHandle)
    }

    /**
     * Invokes the original (unhooked) version of a hooked method.
     * @param hookHandle Handle returned by hookMethod
     * @param thisObject The object to invoke on (null for static)
     * @param args Arguments to pass
     * @return Return value from original method
     */
    fun invokeOriginal(hookHandle: Long, thisObject: Any?, args: Array<Any?>?): Any? {
        check(isInitialized) { "NativeBridge not initialized" }
        return nativeInvokeOriginal(hookHandle, thisObject, args)
    }

    /**
     * Deoptimizes (decompiles from JIT to interpreter) a method.
     * Needed for some methods that must be hooked.
     */
    fun deoptimize(method: java.lang.reflect.Member) {
        if (!isInitialized) return
        try {
            nativeDeoptimize(method)
        } catch (e: Exception) {
            Timber.w(e, "Deoptimize failed for %s", method)
        }
    }

    // ---- JNI declarations ----

    @JvmStatic
    private external fun nativeInit(): Boolean

    @JvmStatic
    private external fun nativeHookMethod(
        targetMethod: java.lang.reflect.Member,
        hookMethod: java.lang.reflect.Method,
        callbackObject: Any?
    ): Long

    @JvmStatic
    private external fun nativeUnhookMethod(hookHandle: Long)

    @JvmStatic
    private external fun nativeInvokeOriginal(
        hookHandle: Long,
        thisObject: Any?,
        args: Array<Any?>?
    ): Any?

    @JvmStatic
    private external fun nativeDeoptimize(method: java.lang.reflect.Member)
}
