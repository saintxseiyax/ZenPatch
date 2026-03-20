#pragma once

#include <jni.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// --- JNI Method Declarations ---

/**
 * Initialize LSPlant and resolve ART symbols.
 * Must be called before any hook operations.
 * @return JNI_TRUE on success
 */
JNIEXPORT jboolean JNICALL
Java_dev_zenpatch_runtime_NativeBridge_nativeInit(JNIEnv *env, jclass cls);

/**
 * Hook a Java method using LSPlant.
 * @param targetMethod java.lang.reflect.Member (Method or Constructor)
 * @param hookMethod The replacement method to call
 * @param callbackObject The object owning the hook method
 * @return Hook handle (opaque long), or 0 on failure
 */
JNIEXPORT jlong JNICALL
Java_dev_zenpatch_runtime_NativeBridge_nativeHookMethod(
    JNIEnv *env, jclass cls,
    jobject targetMethod,
    jobject hookMethod,
    jobject callbackObject);

/**
 * Unhook a previously installed hook.
 * @param hookHandle Handle returned by nativeHookMethod
 */
JNIEXPORT void JNICALL
Java_dev_zenpatch_runtime_NativeBridge_nativeUnhookMethod(
    JNIEnv *env, jclass cls,
    jlong hookHandle);

/**
 * Invoke the original (pre-hook) implementation of a hooked method.
 * @param hookHandle Handle returned by nativeHookMethod
 * @param thisObject The object instance (null for static)
 * @param args Method arguments
 * @return Original method's return value
 */
JNIEXPORT jobject JNICALL
Java_dev_zenpatch_runtime_NativeBridge_nativeInvokeOriginal(
    JNIEnv *env, jclass cls,
    jlong hookHandle,
    jobject thisObject,
    jobjectArray args);

/**
 * Deoptimize (remove JIT compilation of) a method so it can be hooked.
 */
JNIEXPORT void JNICALL
Java_dev_zenpatch_runtime_NativeBridge_nativeDeoptimize(
    JNIEnv *env, jclass cls,
    jobject method);

/**
 * Bypass hidden API restrictions for the current process.
 */
JNIEXPORT void JNICALL
Java_dev_zenpatch_runtime_HiddenApiBypass_nativeBypassHiddenApis(
    JNIEnv *env, jclass cls);

#ifdef __cplusplus
}
#endif
