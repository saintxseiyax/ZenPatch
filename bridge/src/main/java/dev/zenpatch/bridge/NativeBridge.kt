// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.bridge

import android.content.Context
import android.util.Log
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * Kotlin JNI interface to the ZenPatch native bridge library.
 *
 * Wraps native functions exposed by `libzenpatch_bridge.so` and provides a
 * Kotlin-friendly API with null-safety and error handling.
 *
 * The library is loaded lazily via [init].  If loading fails, all hook operations
 * become no-ops and the runtime degrades gracefully without crashing.
 *
 * HOOK CALLBACK CONTRACT
 * ----------------------
 * The [HookCallback] interface is the Kotlin-facing API.  The native layer
 * (LSPlant) requires a Java/Kotlin object that exposes a method with the exact
 * signature:
 *
 *   public Object callback(Object[] args)
 *
 * [HookCallbackAdapter] is a concrete adapter that implements this contract and
 * delegates to the [HookCallback] before/after split.  Pass an instance of
 * [HookCallbackAdapter] (not a raw [HookCallback]) to [hookMethod].
 */
object NativeBridge {

    private const val TAG = "ZP_NativeBridge"
    private const val LIBRARY_NAME = "zenpatch_bridge"

    @Volatile private var isLoaded = false
    @Volatile private var isInitialised = false

    /**
     * Load the native library and initialise LSPlant.
     *
     * This must be called from `Application.attachBaseContext()` or `onCreate()`
     * before any hook operations are attempted.
     *
     * @param context Application context, forwarded to the native layer for
     *   diagnostics and ART symbol resolution.
     */
    fun init(context: Context) {
        if (isInitialised) return

        try {
            System.loadLibrary(LIBRARY_NAME)
            isLoaded = true
            Log.i(TAG, "Native bridge '$LIBRARY_NAME' loaded successfully")

            val success = nativeInit(context)
            isInitialised = success
            if (success) {
                Log.i(TAG, "LSPlant hook engine initialised")
            } else {
                Log.w(TAG, "nativeInit() returned false – hook engine unavailable")
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load $LIBRARY_NAME: ${e.message}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception loading $LIBRARY_NAME: ${e.message}")
        }
    }

    /**
     * Check whether the native bridge is ready for use.
     */
    val isReady: Boolean get() = isInitialised

    /**
     * Hook the given [method] so that [callback] is invoked for each call.
     *
     * The [callback] must be an instance of [HookCallbackAdapter] (or any object
     * whose class exposes a `public Object callback(Object[] args)` method).
     *
     * @param method   The [Member] (Method or Constructor) to hook.
     * @param callback An [HookCallbackAdapter] that will receive hook events.
     * @return A positive handle identifying this hook, or 0 on failure.
     */
    fun hookMethod(method: Member, callback: HookCallbackAdapter): Long {
        if (!isInitialised) {
            Log.w(TAG, "hookMethod(): bridge not initialised")
            return 0L
        }
        return nativeHookMethod(method, callback)
    }

    /**
     * Remove a previously installed hook.
     *
     * @param handle Handle returned by [hookMethod].
     * @return `true` if the hook was removed.
     */
    fun unhookMethod(handle: Long): Boolean {
        if (!isInitialised || handle == 0L) return false
        return nativeUnhookMethod(handle)
    }

    /**
     * Check whether a method is currently hooked by LSPlant.
     *
     * @param method The [Member] to check.
     * @return `true` if there is an active hook on the method.
     */
    fun isHooked(method: Member): Boolean {
        if (!isInitialised) return false
        return nativeIsHooked(method)
    }

    /**
     * Deoptimise a method to prevent ART from inlining it into callers.
     *
     * Call this on a method *after* hooking one of its callees when you notice
     * that your hook callback is not being invoked (a sign of inlining).
     *
     * @param method The [Member] to deoptimise.
     * @return `true` if deoptimisation succeeded.
     */
    fun deoptimize(method: Member): Boolean {
        if (!isInitialised) return false
        return nativeDeoptimize(method)
    }

    // =========================================================================
    // Native declarations
    //
    // JNI signatures must exactly match the C++ implementation in bridge.cpp /
    // hook_engine.cpp.  The method/callback parameters are declared as Object
    // on the JNI side (java/lang/Object) to accept any reference type.
    // =========================================================================

    @JvmStatic
    private external fun nativeInit(context: Context): Boolean

    // (Ljava/lang/Object;Ljava/lang/Object;)J
    @JvmStatic
    private external fun nativeHookMethod(method: Any, callback: Any): Long

    // (J)Z
    @JvmStatic
    private external fun nativeUnhookMethod(handle: Long): Boolean

    // (Ljava/lang/Object;)Z
    @JvmStatic
    private external fun nativeIsHooked(method: Any): Boolean

    // (Ljava/lang/Object;)Z
    @JvmStatic
    private external fun nativeDeoptimize(method: Any): Boolean

    // =========================================================================
    // Callback interfaces and adapters
    // =========================================================================

    /**
     * Lifecycle callback for hooked methods.  Implement this to receive
     * before/after events when a hooked method is called.
     *
     * Note: this is the **Kotlin API**.  The actual object passed to LSPlant
     * must be an [HookCallbackAdapter] which adapts this interface to the
     * LSPlant-required `Object callback(Object[])` signature.
     */
    interface HookCallback {
        /**
         * Called before the original method executes.
         *
         * @param thisObject  The receiver (`this`) for instance methods, or `null`
         *   for static methods.
         * @param args        The method arguments in declaration order.
         * @return If non-null, the return value is used as the method's result and
         *   the original method body is **skipped**.  Return `null` to let the
         *   original method execute.
         */
        fun beforeHookedMethod(thisObject: Any?, args: Array<Any?>): Any?

        /**
         * Called after the original method has executed (or after [beforeHookedMethod]
         * returned a non-null short-circuit result).
         *
         * @param thisObject  The receiver, or `null` for static methods.
         * @param args        The method arguments.
         * @param result      The value returned by the (possibly short-circuited) method.
         * @return Replacement return value, or `null` to keep [result].
         */
        fun afterHookedMethod(thisObject: Any?, args: Array<Any?>, result: Any?): Any?
    }

    /**
     * Adapts a [HookCallback] to the method signature required by LSPlant:
     *
     *   public Object callback(Object[] args)
     *
     * LSPlant passes:
     *   - For non-static methods: args[0] = `this`, args[1..n] = method parameters
     *   - For static methods:     args[0..n-1] = method parameters (no `this` slot)
     *
     * The [backupMethod] field is populated by [NativeBridge.hookMethod] internals
     * via the backup reference returned by lsplant::Hook() and stored by the Kotlin
     * wrapper.
     *
     * @param delegate      The [HookCallback] to dispatch to.
     * @param isStatic      Whether the hooked method is static.
     * @param paramCount    Number of parameters of the original method.
     */
    open class HookCallbackAdapter @JvmOverloads constructor(
        private val delegate: HookCallback,
        private val isStatic: Boolean = false,
        private val paramCount: Int = -1,
    ) {
        /**
         * Backup method reference set after [NativeBridge.hookMethod] succeeds.
         * Call this via reflection to invoke the original (un-hooked) method body.
         */
        @Volatile
        var backupMethod: Method? = null

        /**
         * LSPlant callback entry-point.  The signature **must** match exactly:
         *
         *   public Object callback(Object[] args)
         *
         * @param args Combined args array from LSPlant.
         *   Index 0 is `this` for instance methods; pure args start at [1].
         *   For static methods, args start at [0] (no `this` placeholder).
         */
        @Suppress("UNCHECKED_CAST")
        fun callback(args: Array<Any?>): Any? {
            val thisObject: Any?
            val methodArgs: Array<Any?>

            if (isStatic) {
                thisObject = null
                methodArgs = args
            } else {
                thisObject = args.getOrNull(0)
                methodArgs = if (args.size > 1) args.copyOfRange(1, args.size) else emptyArray()
            }

            // Before
            val shortCircuit = delegate.beforeHookedMethod(thisObject, methodArgs)
            val result: Any? = if (shortCircuit != null) {
                shortCircuit
            } else {
                // Call original via backup
                try {
                    backupMethod?.invoke(thisObject, *methodArgs)
                } catch (e: Exception) {
                    Log.e(TAG, "Error invoking backup method: ${e.message}", e)
                    null
                }
            }

            // After
            return delegate.afterHookedMethod(thisObject, methodArgs, result) ?: result
        }
    }
}
