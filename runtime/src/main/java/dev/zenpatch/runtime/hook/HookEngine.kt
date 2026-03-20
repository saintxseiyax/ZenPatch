// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.runtime.hook

import android.util.Log
import dev.zenpatch.bridge.NativeBridge
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * High-level interface to the LSPlant hooking engine via [NativeBridge].
 *
 * Provides a Kotlin-friendly API over the raw JNI bridge. Used internally
 * by [dev.zenpatch.runtime.module.ModuleLoader] and the Xposed API compat layer.
 *
 * Thread-safety: all public methods are safe to call from any thread.
 * Active hooks are tracked in a [ConcurrentHashMap] keyed by [Member].
 */
object HookEngine {

    private const val TAG = "ZP_HookEngine"

    /**
     * Tracks all active hooks: method → list of (callback → UnhookHandle).
     *
     * The outer map is a [ConcurrentHashMap] for lock-free reads.  The inner
     * list is guarded by the HookEngine monitor when mutated.
     */
    private val activeHooks: ConcurrentHashMap<Member, MutableList<Pair<MethodCallback, UnhookHandle>>> =
        ConcurrentHashMap()

    /**
     * Hook the given [method] so that [callback] is invoked before and after
     * the original implementation.
     *
     * @param method The [java.lang.reflect.Method] or [java.lang.reflect.Constructor] to hook.
     * @param callback Callback implementation (before/after invocation).
     * @return An unhook handle, or null if the hook failed (e.g. bridge not ready).
     */
    fun hook(method: Member, callback: MethodCallback): UnhookHandle? {
        if (!NativeBridge.isReady) {
            Log.w(TAG, "hook(): NativeBridge not ready, skipping hook for $method")
            return null
        }

        val isStatic = when (method) {
            is Method -> Modifier.isStatic(method.modifiers)
            else -> false // Constructors are never static in the LSPlant sense
        }
        val paramCount = when (method) {
            is Method -> method.parameterCount
            is java.lang.reflect.Constructor<*> -> method.parameterCount
            else -> -1
        }

        // Build the NativeBridge.HookCallback adapter that bridges
        // MethodCallback's shared-param-object semantics to NativeBridge's
        // return-value-as-short-circuit contract.
        val hookCallback = object : NativeBridge.HookCallback {
            override fun beforeHookedMethod(thisObject: Any?, args: Array<Any?>): Any? {
                val param = MethodHookParam(
                    method = method,
                    thisObject = thisObject,
                    args = args,
                    result = null,
                    throwable = null,
                    shouldSkipOriginal = false
                )
                try {
                    callback.beforeHookedMethod(param)
                } catch (t: Throwable) {
                    Log.e(TAG, "beforeHookedMethod threw unexpectedly", t)
                    param.throwable = t
                    param.result = null
                    param.shouldSkipOriginal = true
                }
                // Signal skip-original by returning a non-null ResultHolder when the callback
                // explicitly requested it (shouldSkipOriginal) or set a non-null result/throwable.
                return if (param.shouldSkipOriginal || param.throwable != null || param.result != null) {
                    // Wrap in a sentinel so HookCallbackAdapter knows to skip the original.
                    ResultHolder(param.result, param.throwable)
                } else {
                    null
                }
            }

            override fun afterHookedMethod(thisObject: Any?, args: Array<Any?>, result: Any?): Any? {
                // Unwrap a ResultHolder that may have been returned by beforeHookedMethod
                // and passed through HookCallbackAdapter as the result.
                val actualResult: Any?
                val priorThrowable: Throwable?
                if (result is ResultHolder) {
                    actualResult = result.value
                    priorThrowable = result.throwable
                } else {
                    actualResult = result
                    priorThrowable = null
                }

                val param = MethodHookParam(
                    method = method,
                    thisObject = thisObject,
                    args = args,
                    result = actualResult,
                    throwable = priorThrowable
                )
                try {
                    callback.afterHookedMethod(param)
                } catch (t: Throwable) {
                    Log.e(TAG, "afterHookedMethod threw unexpectedly", t)
                }
                // If there's a throwable, propagate it by returning a ResultHolder;
                // callers can check for this type. The HookCallbackAdapter simply
                // returns whatever afterHookedMethod returns, so our wrapper is
                // transparent here — the native trampoline will ultimately use
                // the returned value as the Java return value.
                return if (param.throwable != null) {
                    ResultHolder(null, param.throwable)
                } else {
                    param.result
                }
            }
        }

        val adapter = NativeBridge.HookCallbackAdapter(
            delegate = hookCallback,
            isStatic = isStatic,
            paramCount = paramCount
        )

        val nativeHandle = NativeBridge.hookMethod(method, adapter)
        if (nativeHandle == 0L) {
            Log.e(TAG, "NativeBridge.hookMethod() returned 0 for $method")
            return null
        }

        val handle = UnhookHandle(method, nativeHandle, adapter)

        // Register in activeHooks
        activeHooks.compute(method) { _, existing ->
            val list = existing ?: mutableListOf()
            list.add(Pair(callback, handle))
            list
        }

        Log.d(TAG, "Hooked $method (handle=$nativeHandle)")
        return handle
    }

    /**
     * Remove a previously installed hook.
     *
     * @param handle The handle returned by [hook].
     */
    fun unhook(handle: UnhookHandle) {
        val removed = NativeBridge.unhookMethod(handle.nativeHandle)
        if (!removed) {
            Log.w(TAG, "unhook(): NativeBridge.unhookMethod() returned false for handle=${handle.nativeHandle}")
        }

        // Remove from tracking map
        activeHooks.compute(handle.method) { _, existing ->
            existing?.removeIf { (_, h) -> h === handle }
            if (existing.isNullOrEmpty()) null else existing
        }

        Log.d(TAG, "Unhooked ${handle.method} (handle=${handle.nativeHandle})")
    }

    /**
     * Return the [NativeBridge.HookCallbackAdapter] associated with an active
     * hook.  Used by [dev.zenpatch.runtime.XposedBridge] to call the backup method.
     *
     * @param method The hooked method.
     * @param callback The original [MethodCallback] registered via [hook].
     * @return The adapter, or null if no matching hook is found.
     */
    fun getAdapter(method: Member, callback: MethodCallback): NativeBridge.HookCallbackAdapter? {
        return activeHooks[method]
            ?.firstOrNull { (cb, _) -> cb === callback }
            ?.second
            ?.adapter
    }

    // ---- Inner types --------------------------------------------------------------------

    /** Opaque handle to a live hook, used to unhook later. */
    class UnhookHandle internal constructor(
        /** The hooked method. */
        val method: Member,
        /** Native handle returned by [NativeBridge.hookMethod]; 0 indicates failure. */
        val nativeHandle: Long,
        /** The adapter passed to NativeBridge; exposes [backupMethod] for invokeOriginalMethod. */
        val adapter: NativeBridge.HookCallbackAdapter
    )

    /** Callback interface for method hooks. */
    interface MethodCallback {
        /** Called before the original method body executes. */
        fun beforeHookedMethod(param: MethodHookParam) {}
        /** Called after the original method body executes (even if it threw). */
        fun afterHookedMethod(param: MethodHookParam) {}
    }

    /** Encapsulates the invocation context for a hooked method call. */
    data class MethodHookParam(
        val method: Member,
        val thisObject: Any?,
        val args: Array<Any?>,
        var result: Any?,
        var throwable: Throwable?,
        /**
         * When true, the original method body will be skipped after [MethodCallback.beforeHookedMethod]
         * returns, regardless of whether [result] is null.
         *
         * This mirrors [de.robv.android.xposed.XC_MethodHook.MethodHookParam.shouldReturnEarly]
         * semantics: a before-hook can skip the original by calling setResult(null).
         */
        var shouldSkipOriginal: Boolean = false
    )

    /**
     * Internal wrapper used to carry a (value, throwable) pair through the
     * [NativeBridge.HookCallbackAdapter] return-value channel.
     *
     * When [beforeHookedMethod] or [afterHookedMethod] needs to convey a
     * throwable (or a null result that should skip the original), the adapter
     * wraps it in a [ResultHolder] so downstream code can distinguish between
     * "no override" (null) and "override to null" (ResultHolder(null, null)).
     */
    internal data class ResultHolder(
        val value: Any?,
        val throwable: Throwable?
    )
}
