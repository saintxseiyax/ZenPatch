// SPDX-License-Identifier: GPL-3.0-only
package dev.zenpatch.runtime.hook

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Concrete [XposedBridge.Impl] backed by [HookEngine] and [dev.zenpatch.bridge.NativeBridge].
 *
 * Registered into [XposedBridge] via [XposedBridge.setImpl] during runtime initialisation
 * (see [dev.zenpatch.runtime.ZenPatchRuntime.init]).
 *
 * ## Threading
 * All public methods are thread-safe.  The [activeHooks] map is a [ConcurrentHashMap];
 * individual entries (list of handles per method) are mutated under per-entry synchronisation
 * inside [HookEngine] via its `compute` calls.
 *
 * ## Hook lifecycle
 *  1. [hookMethod] adapts [XC_MethodHook] → [HookEngine.MethodCallback] and delegates to [HookEngine.hook].
 *  2. [unhookMethod] looks up the [HookEngine.UnhookHandle] stored in [activeHooks] and
 *     delegates to [HookEngine.unhook].
 *  3. [invokeOriginalMethod] retrieves the [dev.zenpatch.bridge.NativeBridge.HookCallbackAdapter.backupMethod]
 *     from the stored handle and invokes it via reflection.
 */
class XposedBridgeImpl : XposedBridge.Impl {

    private val TAG = "ZP_XposedBridgeImpl"

    /**
     * Tracks all active (hookMethod, XC_MethodHook) → UnhookHandle mappings.
     *
     * The outer [ConcurrentHashMap] is keyed by a [HookKey] that combines the [Member]
     * (using [Member.equals] semantics) with the [XC_MethodHook] instance (identity).
     */
    private val activeHooks = ConcurrentHashMap<HookKey, HookEngine.UnhookHandle>()

    // ---- Logging -----------------------------------------------------------------------

    override fun log(text: String) {
        Log.i(XposedBridge.TAG, text)
    }

    override fun log(t: Throwable) {
        Log.e(XposedBridge.TAG, t.message, t)
    }

    // ---- Hooking -----------------------------------------------------------------------

    override fun hookMethod(hookMethod: Member, xcHook: XC_MethodHook) {
        val engineCallback = object : HookEngine.MethodCallback {
            override fun beforeHookedMethod(param: HookEngine.MethodHookParam) {
                val xParam = toXParam(hookMethod, param)
                // callBeforeHookedMethod catches any Throwable internally and stores it on xParam
                xcHook.callBeforeHookedMethod(xParam)
                // Write outcomes back to the engine param using the public accessors.
                // shouldReturnEarly covers the case where setResult(null) was called to
                // skip the original method while returning null.
                param.result = xParam.result
                param.throwable = xParam.throwable
                param.shouldSkipOriginal = xParam.shouldReturnEarly()
            }

            override fun afterHookedMethod(param: HookEngine.MethodHookParam) {
                val xParam = toXParam(hookMethod, param)
                // callAfterHookedMethod catches any Throwable internally and stores it on xParam
                xcHook.callAfterHookedMethod(xParam)
                // Write outcomes back to the engine param using the public accessors
                param.result = xParam.result
                param.throwable = xParam.throwable
                // No shouldSkipOriginal for afterHookedMethod — original has already run
            }
        }

        val handle = HookEngine.hook(hookMethod, engineCallback)
        if (handle == null) {
            Log.e(TAG, "hookMethod(): HookEngine.hook() returned null for $hookMethod")
            return
        }
        activeHooks[HookKey(hookMethod, xcHook)] = handle
    }

    override fun unhookMethod(hookMethod: Member, xcHook: XC_MethodHook) {
        val handle = activeHooks.remove(HookKey(hookMethod, xcHook))
        if (handle == null) {
            Log.w(TAG, "unhookMethod(): no active hook found for $hookMethod")
            return
        }
        HookEngine.unhook(handle)
    }

    @Throws(Throwable::class)
    override fun invokeOriginalMethod(method: Member, thisObject: Any?, args: Array<Any?>?): Any? {
        // Find the backup method from any active hook on this member
        var backupMethod: Method? = null
        for ((key, handle) in activeHooks) {
            if (key.method == method) {
                backupMethod = handle.adapter.backupMethod
                if (backupMethod != null) break
            }
        }

        if (backupMethod == null) {
            throw IllegalStateException(
                "invokeOriginalMethod(): no backup method found for $method. " +
                "Ensure the method is hooked before calling invokeOriginalMethod()."
            )
        }

        backupMethod.isAccessible = true
        return try {
            backupMethod.invoke(thisObject, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    // ---- Helpers -----------------------------------------------------------------------

    /**
     * Converts a [HookEngine.MethodHookParam] to a [XC_MethodHook.MethodHookParam].
     *
     * The result is pre-populated with any result/throwable already set on the engine param
     * (important for the after-hook when the original method has already executed).
     */
    private fun toXParam(
        method: Member,
        engineParam: HookEngine.MethodHookParam
    ): XC_MethodHook.MethodHookParam {
        val xParam = XC_MethodHook.MethodHookParam(method, engineParam.thisObject, engineParam.args)
        engineParam.result?.let { xParam.setResult(it) }
        engineParam.throwable?.let { xParam.setThrowable(it) }
        return xParam
    }

    /**
     * Composite key used by [activeHooks].
     *
     * [method] equality uses [Member.equals]; [xcHook] uses **identity** so that multiple
     * independent callbacks on the same method are tracked separately.
     */
    private data class HookKey(
        val method: Member,
        val xcHook: XC_MethodHook
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HookKey) return false
            return method == other.method && xcHook === other.xcHook
        }

        override fun hashCode(): Int =
            31 * method.hashCode() + System.identityHashCode(xcHook)
    }
}
