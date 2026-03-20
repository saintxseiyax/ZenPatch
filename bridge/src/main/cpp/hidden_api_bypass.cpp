#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>
#include <sys/system_properties.h>

#define LOG_TAG "ZenPatch_HAB"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Forward declaration
static void tryAlternativeBypass(JNIEnv* env);

/**
 * Native implementation of hidden API bypass.
 * Calls VMRuntime.setHiddenApiExemptions(["L"]) via JNI, bypassing the
 * Java-level check that would otherwise block it on API 28+.
 *
 * Strategy: The setHiddenApiExemptions method itself is hidden, but we can
 * call it via JNI if we resolve it through dalvik.system.VMRuntime.
 * The JNI path has different enforcement from the Java reflection path.
 */
extern "C"
JNIEXPORT void JNICALL
Java_dev_zenpatch_runtime_HiddenApiBypass_nativeBypassHiddenApis(
        JNIEnv *env, jclass cls) {

    // Method 1: Set hidden API exemptions via JNI (bypasses Java-level check)
    jclass vm_runtime_class = env->FindClass("dalvik/system/VMRuntime");
    if (!vm_runtime_class) {
        env->ExceptionClear();
        LOGW("VMRuntime class not found via JNI (unexpected)");
        return;
    }

    jmethodID get_runtime = env->GetStaticMethodID(
        vm_runtime_class,
        "getRuntime",
        "()Ldalvik/system/VMRuntime;");

    if (!get_runtime) {
        env->ExceptionClear();
        LOGW("VMRuntime.getRuntime() not found");
        return;
    }

    jobject vm_runtime = env->CallStaticObjectMethod(vm_runtime_class, get_runtime);
    if (!vm_runtime || env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("Failed to get VMRuntime instance");
        return;
    }

    jmethodID set_exemptions = env->GetMethodID(
        vm_runtime_class,
        "setHiddenApiExemptions",
        "([Ljava/lang/String;)V");

    if (!set_exemptions) {
        env->ExceptionClear();
        // Method may have been renamed in newer Android versions
        LOGW("setHiddenApiExemptions not found, trying alternative...");
        tryAlternativeBypass(env);
        return;
    }

    // Exempt all APIs: prefix "L" matches all classes
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray exemptions = env->NewObjectArray(1, string_class, nullptr);
    jstring exempt_all = env->NewStringUTF("L");
    env->SetObjectArrayElement(exemptions, 0, exempt_all);

    env->CallVoidMethod(vm_runtime, set_exemptions, exemptions);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGW("setHiddenApiExemptions threw exception, trying fallback");
        tryAlternativeBypass(env);
    } else {
        LOGD("Hidden API bypass: setHiddenApiExemptions([\"L\"]) succeeded");
    }

    env->DeleteLocalRef(exempt_all);
    env->DeleteLocalRef(exemptions);
    env->DeleteLocalRef(vm_runtime);
    env->DeleteLocalRef(vm_runtime_class);
}

/**
 * Alternative bypass: use system property dalvik.vm.dex2oat flag
 * or direct native manipulation of the enforcement policy.
 */
static void tryAlternativeBypass(JNIEnv* env) {
    // Method 2: dlopen libart and resolve the enforcement setter directly
    void* libart = dlopen("libart.so", RTLD_NOW | RTLD_NOLOAD);
    if (!libart) return;

    // Try to find and call the internal enforcement setter
    // The symbol name varies by Android version
    typedef void (*SetHiddenApiPolicy)(int);
    SetHiddenApiPolicy setter = nullptr;

    const char* symbols[] = {
        "_ZN3art7Runtime25SetHiddenApiEnforcementPolicyENS_9hiddenapi14EnforcementPolicyE",
        "_ZN3art7Runtime25SetHiddenApiEnforcementPolicyEi",
        nullptr
    };

    for (const char** sym = symbols; *sym != nullptr; sym++) {
        setter = reinterpret_cast<SetHiddenApiPolicy>(dlsym(libart, *sym));
        if (setter) {
            LOGD("Found hidden API policy setter: %s", *sym);
            setter(0); // EnforcementPolicy::kNoChecks = 0
            LOGD("Hidden API enforcement disabled via native setter");
            break;
        }
    }

    if (!setter) {
        LOGW("No hidden API policy setter found in libart.so");
    }
}
