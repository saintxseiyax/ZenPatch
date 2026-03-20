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
 * Concrete [de.robv.android.xposed.XposedBridgeImpl] backed by [HookEngine] and
 * the runtime NativeBridge.
 *
 * Registered into [XposedBridge] via [XposedBridge.setImpl] during runtime initialisation.
 */
class XposedBridgeImpl : de.robv.android.xposed.XposedBridgeImpl {

    private val TAG = "ZP_XposedBridgeImpl"

    /**
     * Tracks all active (hookMethod, XC_MethodHook) → UnhookHandle mappings.
     */
    private val activeHooks = ConcurrentHashMap<HookKey, HookEngine.UnhookHandle>()

    // ---- Hooking ----

    override fun hookMethod(method: Member, callback: XC_MethodHook): XC_MethodHook.Unhook {
        val engineCallback = object : HookEngine.MethodCallback {
            override fun beforeHookedMethod(param: HookEngine.MethodHookParam) {
                val xParam = XC_MethodHook.MethodHookParam()
                xParam.method = method
                xParam.thisObject = param.thisObject
                xParam.args = param.args
                try {
                    // Use reflection to call protected beforeHookedMethod
                    val m = XC_MethodHook::class.java.getDeclaredMethod(
                        "beforeHookedMethod",
                        XC_MethodHook.MethodHookParam::class.java
                    )
                    m.isAccessible = true
                    m.invoke(callback, xParam)
                } catch (e: InvocationTargetException) {
                    Log.e(TAG, "beforeHookedMethod threw", e.cause)
                    param.throwable = e.cause
                    param.shouldSkipOriginal = true
                    return
                } catch (t: Throwable) {
                    Log.e(TAG, "beforeHookedMethod threw unexpectedly", t)
                    param.throwable = t
                    param.shouldSkipOriginal = true
                    return
                }
                param.result = xParam.result
                param.throwable = xParam.throwable
                param.shouldSkipOriginal = xParam.isReturnEarly
            }

            override fun afterHookedMethod(param: HookEngine.MethodHookParam) {
                val xParam = XC_MethodHook.MethodHookParam()
                xParam.method = method
                xParam.thisObject = param.thisObject
                xParam.args = param.args
                if (param.result != null) xParam.setResult(param.result)
                if (param.throwable != null) xParam.setThrowable(param.throwable!!)
                try {
                    val m = XC_MethodHook::class.java.getDeclaredMethod(
                        "afterHookedMethod",
                        XC_MethodHook.MethodHookParam::class.java
                    )
                    m.isAccessible = true
                    m.invoke(callback, xParam)
                } catch (e: InvocationTargetException) {
                    Log.e(TAG, "afterHookedMethod threw", e.cause)
                } catch (t: Throwable) {
                    Log.e(TAG, "afterHookedMethod threw unexpectedly", t)
                }
                param.result = xParam.result
                param.throwable = xParam.throwable
            }
        }

        val handle = HookEngine.hook(method, engineCallback)
        val unhook = XC_MethodHook.Unhook(callback, method)
        if (handle != null) {
            activeHooks[HookKey(method, callback)] = handle
        } else {
            Log.e(TAG, "hookMethod(): HookEngine.hook() returned null for $method")
        }
        return unhook
    }

    override fun unhookMethod(unhook: XC_MethodHook.Unhook) {
        val handle = activeHooks.remove(HookKey(unhook.hookedMethod, unhook.callback))
        if (handle != null) {
            HookEngine.unhook(handle)
        }
    }

    @Throws(Throwable::class)
    override fun invokeOriginalMethod(method: Member, thisObject: Any?, args: Array<Any?>?): Any? {
        // Find any active hook for this method to get its native handle
        for ((key, handle) in activeHooks) {
            if (key.method == method) {
                return HookEngine.invokeOriginal(handle, thisObject, args)
            }
        }

        // No hook active, call directly via reflection
        return when (method) {
            is Method -> {
                method.isAccessible = true
                method.invoke(thisObject, *(args ?: emptyArray()))
            }
            is java.lang.reflect.Constructor<*> -> {
                method.isAccessible = true
                method.newInstance(*(args ?: emptyArray()))
            }
            else -> null
        }
    }

    // ---- Helper types ----

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
