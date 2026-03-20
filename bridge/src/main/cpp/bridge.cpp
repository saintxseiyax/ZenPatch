// SPDX-License-Identifier: GPL-3.0-only
/**
 * bridge.cpp – JNI_OnLoad and native method registration.
 *
 * Loaded via System.loadLibrary("zenpatch_bridge") during runtime bootstrap.
 * Performs early initialisation (hidden API bypass, diagnostic logging) and
 * registers all native methods for dev.zenpatch.bridge.NativeBridge.
 *
 * LSPlant is then fully initialised via the explicit nativeInit() call from
 * Kotlin once the Application context is available.
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <cstdint>

#define LOG_TAG "ZenPatch_Bridge"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, fmt, ##__VA_ARGS__)

// ---------------------------------------------------------------------------
// Forward declarations from other translation units
// ---------------------------------------------------------------------------
extern "C" {
    // hook_engine.cpp
    JNIEXPORT jboolean JNICALL Java_dev_zenpatch_bridge_NativeBridge_nativeInit(
        JNIEnv* env, jclass clazz, jobject context);
    JNIEXPORT jlong    JNICALL Java_dev_zenpatch_bridge_NativeBridge_nativeHookMethod(
        JNIEnv* env, jclass clazz, jobject method, jobject callback);
    JNIEXPORT jboolean JNICALL Java_dev_zenpatch_bridge_NativeBridge_nativeUnhookMethod(
        JNIEnv* env, jclass clazz, jlong handle);
    JNIEXPORT jboolean JNICALL Java_dev_zenpatch_bridge_NativeBridge_nativeIsHooked(
        JNIEnv* env, jclass clazz, jobject method);
    JNIEXPORT jboolean JNICALL Java_dev_zenpatch_bridge_NativeBridge_nativeDeoptimize(
        JNIEnv* env, jclass clazz, jobject method);

    // hidden_api_bypass.cpp
    bool activate_hidden_api_bypass(JNIEnv* env);
}

// ---------------------------------------------------------------------------
// Native method table for dev.zenpatch.bridge.NativeBridge
// ---------------------------------------------------------------------------
static const JNINativeMethod kNativeBridgeMethods[] = {
    {
        "nativeInit",
        "(Landroid/content/Context;)Z",
        reinterpret_cast<void*>(Java_dev_zenpatch_bridge_NativeBridge_nativeInit)
    },
    {
        "nativeHookMethod",
        "(Ljava/lang/Object;Ljava/lang/Object;)J",
        reinterpret_cast<void*>(Java_dev_zenpatch_bridge_NativeBridge_nativeHookMethod)
    },
    {
        "nativeUnhookMethod",
        "(J)Z",
        reinterpret_cast<void*>(Java_dev_zenpatch_bridge_NativeBridge_nativeUnhookMethod)
    },
    {
        "nativeIsHooked",
        "(Ljava/lang/Object;)Z",
        reinterpret_cast<void*>(Java_dev_zenpatch_bridge_NativeBridge_nativeIsHooked)
    },
    {
        "nativeDeoptimize",
        "(Ljava/lang/Object;)Z",
        reinterpret_cast<void*>(Java_dev_zenpatch_bridge_NativeBridge_nativeDeoptimize)
    },
};

// ---------------------------------------------------------------------------
// Helper: log ABI and SDK version for diagnostics
// ---------------------------------------------------------------------------
static void log_runtime_info(JNIEnv* env) {
    // ABI
    const char* abi = "unknown";
#if defined(__aarch64__)
    abi = "arm64-v8a";
#elif defined(__arm__)
    abi = "armeabi-v7a";
#elif defined(__x86_64__)
    abi = "x86_64";
#elif defined(__i386__)
    abi = "x86";
#elif defined(__riscv)
    abi = "riscv64";
#endif
    LOGI("Native bridge compiled for ABI: %s", abi);

    // SDK version from Build.VERSION
    jclass buildVersionClass = env->FindClass("android/os/Build$VERSION");
    if (buildVersionClass) {
        jfieldID sdkField = env->GetStaticFieldID(buildVersionClass, "SDK_INT", "I");
        if (sdkField) {
            jint sdk = env->GetStaticIntField(buildVersionClass, sdkField);
            LOGI("Android SDK_INT: %d", sdk);
        }
        env->DeleteLocalRef(buildVersionClass);
    } else {
        env->ExceptionClear();
    }
}

// ===========================================================================
// JNI_OnLoad – called by the JVM when this .so is first loaded
// ===========================================================================

/**
 * JNI_OnLoad is invoked by the Android runtime when System.loadLibrary() is
 * called.  The JNIEnv obtained here has *no* hidden API restrictions, which
 * makes it the perfect time to install the bypass and cache class references.
 *
 * LSPlant::Init() is intentionally deferred to nativeInit() because it
 * requires ART to be fully settled – some devices crash if Init() is called
 * this early.  However we *do* activate the hidden API bypass here so that
 * all subsequent JNI/reflection calls (including during Init()) are unrestricted.
 *
 * @param vm       JavaVM provided by the runtime.
 * @param reserved Always null.
 * @return JNI_VERSION_1_6 on success, JNI_ERR on failure.
 */
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        // Cannot even get a JNIEnv – something is fundamentally wrong.
        return JNI_ERR;
    }

    LOGI("JNI_OnLoad: ZenPatch native bridge loading...");

    // ---- 1. Log runtime info -------------------------------------------
    log_runtime_info(env);

    // ---- 2. Activate hidden API bypass ---------------------------------
    // The JNIEnv in JNI_OnLoad is unrestricted – best time to call this.
    if (!activate_hidden_api_bypass(env)) {
        LOGW("JNI_OnLoad: hidden API bypass could not be fully activated – "
             "continuing, will retry in nativeInit()");
        // Non-fatal: bypass may succeed later; don't abort load.
    }

    // ---- 3. Register native methods ------------------------------------
    jclass nativeBridgeClass = env->FindClass("dev/zenpatch/bridge/NativeBridge");
    if (!nativeBridgeClass) {
        LOGE("JNI_OnLoad: dev.zenpatch.bridge.NativeBridge class not found");
        env->ExceptionClear();
        // Fall through – manual name-mangled registration in hook_engine.cpp
        // still works; explicit registration is belt-and-suspenders.
    } else {
        jint registered = env->RegisterNatives(
            nativeBridgeClass,
            kNativeBridgeMethods,
            static_cast<jint>(sizeof(kNativeBridgeMethods) / sizeof(kNativeBridgeMethods[0])));

        if (registered != JNI_OK) {
            LOGE("JNI_OnLoad: RegisterNatives failed (rc=%d)", registered);
            env->DeleteLocalRef(nativeBridgeClass);
            return JNI_ERR;
        }

        LOGI("JNI_OnLoad: Registered %zu native methods for NativeBridge",
             sizeof(kNativeBridgeMethods) / sizeof(kNativeBridgeMethods[0]));
        env->DeleteLocalRef(nativeBridgeClass);
    }

    LOGI("JNI_OnLoad: ZenPatch native bridge loaded successfully");
    return JNI_VERSION_1_6;
}

/**
 * JNI_OnUnload – cleanup when the library is unloaded (rare on Android).
 */
JNIEXPORT void JNI_OnUnload(JavaVM* /*vm*/, void* /*reserved*/) {
    LOGI("JNI_OnUnload: ZenPatch native bridge unloading");
    // LSPlant does not expose a public shutdown API; hooks are automatically
    // cleaned up when the process terminates.
}
