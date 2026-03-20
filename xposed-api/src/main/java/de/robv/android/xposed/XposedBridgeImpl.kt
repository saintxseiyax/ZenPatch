package de.robv.android.xposed

import java.lang.reflect.Member

/**
 * Interface for XposedBridge implementation (Provider-Injection-Pattern).
 * The runtime module provides an implementation backed by LSPlant.
 * This avoids circular dependencies between xposed-api and runtime.
 */
interface XposedBridgeImpl {
    fun hookMethod(method: Member, callback: XC_MethodHook): XC_MethodHook.Unhook
    fun unhookMethod(unhook: XC_MethodHook.Unhook)
    fun invokeOriginalMethod(method: Member, thisObject: Any?, args: Array<Any?>?): Any?
}
