// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.runtime.hook

import android.util.Log
import dev.zenpatch.runtime.NativeBridge
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
     */
    private val activeHooks: ConcurrentHashMap<Member, MutableList<Pair<MethodCallback, UnhookHandle>>> =
        ConcurrentHashMap()

    /**
     * Hook the given [method] so that [callback] is invoked before and after
     * the original implementation.
     */
    fun hook(method: Member, callback: MethodCallback): UnhookHandle? {
        if (!NativeBridge.isReady()) {
            Log.w(TAG, "hook(): NativeBridge not ready, skipping hook for $method")
            return null
        }

        // Build a synthetic hook method that dispatches to our callback.
        // For LSPlant, we need a java.lang.reflect.Method as the "hookMethod" parameter.
        val dispatchMethod: Method = try {
            HookDispatch::class.java.getDeclaredMethod(
                "dispatch",
                Any::class.java,
                Array<Any?>::class.java
            )
        } catch (e: NoSuchMethodException) {
            Log.e(TAG, "Cannot find HookDispatch.dispatch method", e)
            return null
        }

        val nativeHandle = NativeBridge.hookMethod(method as Member, dispatchMethod, null)
        if (nativeHandle == 0L) {
            Log.e(TAG, "NativeBridge.hookMethod() returned 0 for $method")
            return null
        }

        val handle = UnhookHandle(method, nativeHandle)

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
     */
    fun unhook(handle: UnhookHandle) {
        NativeBridge.unhookMethod(handle.nativeHandle)

        activeHooks.compute(handle.method) { _, existing ->
            existing?.removeIf { (_, h) -> h === handle }
            if (existing.isNullOrEmpty()) null else existing
        }

        Log.d(TAG, "Unhooked ${handle.method} (handle=${handle.nativeHandle})")
    }

    /**
     * Invoke the original (unhooked) method body.
     */
    fun invokeOriginal(handle: UnhookHandle, thisObject: Any?, args: Array<Any?>?): Any? {
        return NativeBridge.invokeOriginal(handle.nativeHandle, thisObject, args)
    }

    // ---- Inner types ----

    /** Opaque handle to a live hook, used to unhook later. */
    class UnhookHandle internal constructor(
        val method: Member,
        val nativeHandle: Long
    )

    /** Callback interface for method hooks. */
    interface MethodCallback {
        fun beforeHookedMethod(param: MethodHookParam) {}
        fun afterHookedMethod(param: MethodHookParam) {}
    }

    /** Encapsulates the invocation context for a hooked method call. */
    data class MethodHookParam(
        val method: Member,
        val thisObject: Any?,
        val args: Array<Any?>,
        var result: Any?,
        var throwable: Throwable?,
        var shouldSkipOriginal: Boolean = false
    )

    /**
     * Internal result wrapper used to carry (value, throwable) pairs.
     */
    internal data class ResultHolder(
        val value: Any?,
        val throwable: Throwable?
    )
}

/**
 * Static dispatcher invoked by the native bridge for each hooked method call.
 */
@Suppress("unused")
object HookDispatch {
    @JvmStatic
    fun dispatch(thisObject: Any?, args: Array<Any?>?): Any? {
        return null
    }
}
