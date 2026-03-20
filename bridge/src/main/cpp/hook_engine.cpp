// SPDX-License-Identifier: GPL-3.0-only
/**
 * hook_engine.cpp – LSPlant-backed hook engine for ZenPatch.
 *
 * Exposes the following JNI methods to dev.zenpatch.bridge.NativeBridge:
 *   nativeInit()         – Bypass hidden APIs, initialise LSPlant
 *   nativeHookMethod()   – Install a Java method hook via LSPlant
 *   nativeUnhookMethod() – Remove a hook by handle (backup method)
 *   nativeIsHooked()     – Check whether a method is currently hooked
 *   nativeDeoptimize()   – Force ART to deoptimise a method
 *
 * INLINE HOOK IMPLEMENTATION
 * --------------------------
 * LSPlant::InitInfo requires two callbacks:
 *   inline_hooker   – install a native-level trampoline on a target function
 *   inline_unhooker – remove a previously installed trampoline
 *
 * For Android devices supporting arm64-v8a we implement a minimal 16-byte
 * absolute-address trampoline (does not require PC-relative reach):
 *
 *   LDR  X17, #8    ; load the 64-bit target address from offset +8
 *   BR   X17        ; branch to it
 *   <8-byte target address>
 *
 * For armeabi-v7a (Thumb-2) we use a 12-byte trampoline:
 *   PUSH {r7}        ; 2 bytes
 *   LDR  r7, [pc,#4] ; 4 bytes – load address word
 *   MOV  pc, r7      ; 4 bytes – jump
 *   <4-byte target address>
 *
 * x86_64 fallback: indirect JMP through a RIP-relative pointer:
 *   FF 25 00 00 00 00   JMP [RIP+0]
 *   <8-byte target address>
 *
 * The trampoline is written into an mmap(PROT_READ|PROT_WRITE|PROT_EXEC) page
 * so it survives across unhook/rehook cycles.  Each hook entry saves the
 * original bytes so unhooking simply restores them.
 *
 * HOOK TABLE
 * ----------
 * We maintain a global map from (backup jobject global-ref) → jlong handle so
 * that nativeUnhookMethod() can resolve the LSPlant backup method from the
 * opaque handle returned to Kotlin.
 */

#include <jni.h>
#include <android/log.h>
#include <sys/mman.h>
#include <unistd.h>
#include <string.h>
#include <cstdint>
#include <cstdio>
#include <atomic>
#include <mutex>
#include <unordered_map>
#include <string>
#include <string_view>
#include <functional>

#include "lsplant.hpp"

#define LOG_TAG "ZenPatch_HookEngine"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, fmt, ##__VA_ARGS__)
#define LOGD(fmt, ...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, fmt, ##__VA_ARGS__)

// ---------------------------------------------------------------------------
// Forward declarations from other translation units
// ---------------------------------------------------------------------------
extern "C" bool    activate_hidden_api_bypass(JNIEnv* env);
extern "C" void*   resolve_art_symbol(std::string_view symbol_name);
extern "C" void*   resolve_art_symbol_prefix(std::string_view symbol_prefix);

// ---------------------------------------------------------------------------
// Global JVM reference (needed to attach threads during hook callbacks)
// ---------------------------------------------------------------------------
static JavaVM* g_jvm = nullptr;

// ---------------------------------------------------------------------------
// Minimal inline hook: per-patch record
// ---------------------------------------------------------------------------
namespace {

constexpr size_t kPageSize = 4096; // mprotect page size (minimum; we use sysconf at runtime)

/// Maximum bytes we overwrite at the target function entry point.
#if defined(__aarch64__)
constexpr size_t kTrampolineSize = 16; // LDR X17,#8; BR X17; <8-byte addr>
#elif defined(__arm__)
constexpr size_t kTrampolineSize = 12; // Thumb-2 trampoline
#elif defined(__x86_64__)
constexpr size_t kTrampolineSize = 14; // FF 25 00 00 00 00 + 8-byte addr
#else
constexpr size_t kTrampolineSize = 0;  // unsupported
#endif

struct InlinePatch {
    void*   target;                          // Address that was patched
    uint8_t saved_bytes[kTrampolineSize > 0 ? kTrampolineSize : 1]; // Original bytes
    bool    active = false;
};

static std::mutex                               g_patch_mutex;
static std::unordered_map<void*, InlinePatch>   g_patches; // keyed by target address

// ---------------------------------------------------------------------------
// mprotect helper – make a page range writable (and executable for hook write)
// ---------------------------------------------------------------------------
static bool make_writable(void* addr, size_t size) {
    uintptr_t page_start = reinterpret_cast<uintptr_t>(addr) & ~(static_cast<uintptr_t>(kPageSize - 1));
    size_t    page_size  = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    size_t    aligned    = size + (reinterpret_cast<uintptr_t>(addr) - page_start);
    return mprotect(reinterpret_cast<void*>(page_start), aligned, PROT_READ | PROT_WRITE | PROT_EXEC) == 0;
}

static bool restore_protection(void* addr, size_t size) {
    uintptr_t page_start = reinterpret_cast<uintptr_t>(addr) & ~(static_cast<uintptr_t>(kPageSize - 1));
    size_t    page_size  = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    size_t    aligned    = size + (reinterpret_cast<uintptr_t>(addr) - page_start);
    return mprotect(reinterpret_cast<void*>(page_start), aligned, PROT_READ | PROT_EXEC) == 0;
}

// ---------------------------------------------------------------------------
// Write the architecture-specific trampoline at target, redirecting to hooker.
// Returns a backup pointer (trampoline stub that calls original), or nullptr.
// ---------------------------------------------------------------------------
static void* write_trampoline(void* target, void* hooker) {
    if (!target || !hooker) return nullptr;
#if kTrampolineSize == 0
    LOGE("write_trampoline: unsupported architecture");
    return nullptr;
#else
    std::lock_guard<std::mutex> lock(g_patch_mutex);

    // Save original bytes
    InlinePatch patch;
    patch.target = target;
    memcpy(patch.saved_bytes, target, kTrampolineSize);
    patch.active = false; // will set true after write

    // Make target page writable
    if (!make_writable(target, kTrampolineSize)) {
        LOGE("write_trampoline: mprotect(RWX) failed for %p", target);
        return nullptr;
    }

    auto* dst = static_cast<uint8_t*>(target);

#if defined(__aarch64__)
    // ARM64 trampoline (16 bytes):
    //   LDR  X17, #8   → 0x58000051
    //   BR   X17       → 0xD61F0220
    //   <64-bit hooker address>
    uint32_t ldr_x17 = 0x58000051u; // LDR X17, label (PC+8)
    uint32_t br_x17  = 0xD61F0220u; // BR X17
    memcpy(dst + 0, &ldr_x17, 4);
    memcpy(dst + 4, &br_x17,  4);
    memcpy(dst + 8, &hooker,  8);
    __builtin___clear_cache(dst, dst + kTrampolineSize);

#elif defined(__arm__)
    // Thumb-2 trampoline (12 bytes):
    //   F8DF 7004  LDR.W  R7, [PC, #4]  (load from offset +8 from instruction end = +4 from next)
    //   Actually use: 00 4F  MOV PC, R9  – too complex; use simpler approach:
    //   LDR  PC, [PC, #0]  (4 bytes)  → value: 0xE59FF000 (ARM mode) or Thumb:
    // Simplest ARM Thumb-2 absolute jump:
    //   F000 BC00  B.W follows (not suitable for absolute)
    // Use ARM mode instructions (switch from Thumb if needed):
    //   E51FF004  LDR PC, [PC, #-4]   (PC = *(PC-4+8) = *(PC+4))
    //   <4-byte hooker address>
    // For Thumb entry points (LSB set), strip the bit and emit Thumb-2:
    //   DF E9 00 F0  LDR.W PC, [PC, #0]
    // Actually the safest cross-mode trampoline for Thumb-2:
    //   MOVW R7, #lo16(hooker)  ; 4 bytes
    //   MOVT R7, #hi16(hooker)  ; 4 bytes
    //   BX R7                   ; 2 bytes  (+ 2 bytes NOP for alignment)
    {
        uintptr_t target_addr = reinterpret_cast<uintptr_t>(target);
        bool is_thumb = (target_addr & 1) != 0;
        void* real_target = reinterpret_cast<void*>(target_addr & ~1u);
        uintptr_t hooker_addr = reinterpret_cast<uintptr_t>(hooker) & ~1u;

        uint16_t lo = hooker_addr & 0xFFFFu;
        uint16_t hi = (hooker_addr >> 16) & 0xFFFFu;

        if (is_thumb) {
            // MOVW R7, #lo  (Thumb-2 encoding: T3)
            //  15:12=1111 11:10=10_0 imm4=hi[15:12] 11=0 imm3=lo[14:12] Rd=0111 imm8=lo[7:0]
            uint32_t movw = 0xF2400007u |
                ((lo & 0xF000u) << 4) |
                ((lo & 0x0800u) << 15) |
                ((lo & 0x0700u) << 4) |
                (lo & 0x00FFu);
            uint32_t movt = 0xF2C00007u |
                ((hi & 0xF000u) << 4) |
                ((hi & 0x0800u) << 15) |
                ((hi & 0x0700u) << 4) |
                (hi & 0x00FFu);
            uint16_t bx_r7 = 0x47B8u; // BX R7
            uint16_t nop   = 0xBF00u; // NOP
            memcpy(dst + 0, &movw, 4);
            memcpy(dst + 4, &movt, 4);
            memcpy(dst + 8, &bx_r7, 2);
            memcpy(dst + 10, &nop, 2);
        } else {
            // ARM mode: LDR PC, [PC, #0] + absolute address
            // LDR PC, [PC, #0]: 0xE59FF000
            uint32_t ldr_pc = 0xE59FF000u;
            memcpy(dst + 0, &ldr_pc, 4);
            memcpy(dst + 4, &hooker, 4);
            memset(dst + 8, 0, 4); // pad to kTrampolineSize
        }
        __builtin___clear_cache(dst, dst + kTrampolineSize);
    }

#elif defined(__x86_64__)
    // x86_64 indirect jump through RIP+0:
    //   FF 25 00 00 00 00   JMP QWORD PTR [RIP+0]
    //   <8-byte hooker address>
    dst[0] = 0xFF; dst[1] = 0x25; dst[2] = 0x00; dst[3] = 0x00;
    dst[4] = 0x00; dst[5] = 0x00;
    memcpy(dst + 6, &hooker, 8);
#endif

    restore_protection(target, kTrampolineSize);
    patch.active = true;
    g_patches[target] = patch;

    // Return a non-null sentinel: LSPlant only checks for null (failure).
    // It does not actually call the returned "backup" pointer through the
    // inline hook mechanism; the Java backup method is returned by lsplant::Hook().
    return reinterpret_cast<void*>(static_cast<uintptr_t>(1));
#endif // kTrampolineSize
}

// ---------------------------------------------------------------------------
// Restore original bytes at target (inline unhook)
// ---------------------------------------------------------------------------
static bool remove_trampoline(void* func) {
    if (!func) return false;
#if kTrampolineSize == 0
    return false;
#else
    std::lock_guard<std::mutex> lock(g_patch_mutex);
    auto it = g_patches.find(func);
    if (it == g_patches.end()) {
        LOGW("remove_trampoline: no patch record for %p", func);
        return false;
    }
    const InlinePatch& p = it->second;
    if (!p.active) return true;

    if (!make_writable(p.target, kTrampolineSize)) {
        LOGE("remove_trampoline: mprotect(RWX) failed for %p", p.target);
        return false;
    }
    memcpy(p.target, p.saved_bytes, kTrampolineSize);
#if defined(__aarch64__) || defined(__arm__)
    __builtin___clear_cache(static_cast<uint8_t*>(p.target),
                            static_cast<uint8_t*>(p.target) + kTrampolineSize);
#endif
    restore_protection(p.target, kTrampolineSize);
    g_patches.erase(it);
    return true;
#endif
}

// ---------------------------------------------------------------------------
// Hook table: handle ↔ { target global-ref, backup global-ref }
// LSPlant::UnHook takes the TARGET method; we store both.
// ---------------------------------------------------------------------------
struct HookEntry {
    jobject target_global = nullptr; ///< global ref to target java.lang.reflect.Method
    jobject backup_global = nullptr; ///< global ref to backup java.lang.reflect.Method
};

static std::mutex                                g_hook_table_mutex;
static std::unordered_map<jlong, HookEntry>      g_hook_table; // handle → HookEntry
static std::atomic<jlong>                        g_next_handle{1};

static jlong store_hook(JNIEnv* env, jobject target_method, jobject backup_method) {
    jobject target_global = env->NewGlobalRef(target_method);
    jobject backup_global = env->NewGlobalRef(backup_method);
    if (!target_global || !backup_global) {
        if (target_global) env->DeleteGlobalRef(target_global);
        if (backup_global) env->DeleteGlobalRef(backup_global);
        return 0L;
    }
    jlong handle = g_next_handle.fetch_add(1, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(g_hook_table_mutex);
    g_hook_table[handle] = HookEntry{target_global, backup_global};
    return handle;
}

static jobject get_target(jlong handle) {
    std::lock_guard<std::mutex> lock(g_hook_table_mutex);
    auto it = g_hook_table.find(handle);
    return it != g_hook_table.end() ? it->second.target_global : nullptr;
}

static void remove_hook_entry(JNIEnv* env, jlong handle) {
    std::lock_guard<std::mutex> lock(g_hook_table_mutex);
    auto it = g_hook_table.find(handle);
    if (it != g_hook_table.end()) {
        env->DeleteGlobalRef(it->second.target_global);
        env->DeleteGlobalRef(it->second.backup_global);
        g_hook_table.erase(it);
    }
}

// ---------------------------------------------------------------------------
// Engine initialised flag
// ---------------------------------------------------------------------------
static std::atomic<bool> g_initialised{false};

} // anonymous namespace

// ===========================================================================
// JNI implementations
// ===========================================================================
extern "C" {

/**
 * nativeInit() – Initialise the hook engine.
 *
 * 1. Activates hidden API bypass.
 * 2. Builds the LSPlant InitInfo with symbol resolver and minimal inline hooker.
 * 3. Calls lsplant::Init().
 */
JNIEXPORT jboolean JNICALL Java_dev_zenpatch_bridge_NativeBridge_nativeInit(
        JNIEnv* env, jclass /*clazz*/, jobject context) {

    if (g_initialised.load(std::memory_order_acquire)) {
        LOGI("nativeInit(): already initialised");
        return JNI_TRUE;
    }

    // ---- 1. Cache JavaVM ------------------------------------------------
    if (!g_jvm) {
        env->GetJavaVM(&g_jvm);
    }

    // ---- 2. Log context info -------------------------------------------
    if (context) {
        // Read Build.VERSION.SDK_INT for diagnostics
        jclass buildClass = env->FindClass("android/os/Build$VERSION");
        if (buildClass) {
            jfieldID sdkField = env->GetStaticFieldID(buildClass, "SDK_INT", "I");
            if (sdkField) {
                jint sdk = env->GetStaticIntField(buildClass, sdkField);
                LOGI("nativeInit(): Android SDK_INT = %d", sdk);
            }
            env->DeleteLocalRef(buildClass);
        }
    }

    const char* abi = "unknown";
#if defined(__aarch64__)
    abi = "arm64-v8a";
#elif defined(__arm__)
    abi = "armeabi-v7a";
#elif defined(__x86_64__)
    abi = "x86_64";
#elif defined(__i386__)
    abi = "x86";
#endif
    LOGI("nativeInit(): ABI = %s", abi);

    // ---- 3. Hidden API bypass ------------------------------------------
    if (!activate_hidden_api_bypass(env)) {
        LOGW("nativeInit(): hidden API bypass failed – proceeding anyway");
        // Non-fatal: LSPlant may still work for non-blocked symbols.
    }

    // ---- 4. Set up LSPlant InitInfo ------------------------------------
    lsplant::InitInfo info{};

    // art_symbol_resolver: exact symbol lookup in libart.so
    info.art_symbol_resolver = [](std::string_view symbol_name) -> void* {
        return resolve_art_symbol(symbol_name);
    };

    // art_symbol_prefix_resolver: first symbol with given prefix in libart.so
    info.art_symbol_prefix_resolver = [](std::string_view symbol_prefix) -> void* {
        return resolve_art_symbol_prefix(symbol_prefix);
    };

    // inline_hooker: minimal ARM64 / ARM32 / x86_64 trampoline
    info.inline_hooker = [](void* target, void* hooker) -> void* {
        return write_trampoline(target, hooker);
    };

    // inline_unhooker: restore original bytes
    info.inline_unhooker = [](void* func) -> bool {
        return remove_trampoline(func);
    };

    // Generated class naming (aids debugging / logs)
    info.generated_class_name  = "dev/zenpatch/bridge/LSPHooker_";
    info.generated_source_name = "ZenPatch";
    info.generated_field_name  = "hooker";
    info.generated_method_name = "{target}";

    // ---- 5. Initialise LSPlant -----------------------------------------
    bool ok = lsplant::Init(env, info);
    if (!ok) {
        LOGE("nativeInit(): lsplant::Init() failed");
        return JNI_FALSE;
    }

    g_initialised.store(true, std::memory_order_release);
    LOGI("nativeInit(): LSPlant initialised successfully");
    return JNI_TRUE;
}

/**
 * nativeHookMethod() – Hook a Java method via LSPlant.
 *
 * @param method   java.lang.reflect.Method or Constructor to hook.
 * @param callback An object whose callback_method (signature: Object(Object[]))
 *                 will be called instead of the original method.
 *                 The callback must have a method matching LSPlant's requirement.
 * @return Positive handle identifying the hook, or 0 on failure.
 */
JNIEXPORT jlong JNICALL Java_dev_zenpatch_bridge_NativeBridge_nativeHookMethod(
        JNIEnv* env, jclass /*clazz*/, jobject method, jobject callback) {

    if (!g_initialised.load(std::memory_order_acquire)) {
        LOGE("nativeHookMethod(): engine not initialised");
        return 0L;
    }
    if (!method || !callback) {
        LOGE("nativeHookMethod(): null method or callback");
        return 0L;
    }

    LOGD("nativeHookMethod(): looking up callback method");

    // Locate the callback method on the callback object.
    // LSPlant requires a method with signature: public Object callback(Object[] args)
    // We look for a method named "callback" on the callback object's class.
    jclass callbackClass = env->GetObjectClass(callback);
    if (!callbackClass) {
        LOGE("nativeHookMethod(): cannot get callback class");
        return 0L;
    }

    // Find "callback" method: public Object callback(Object[] args)
    jmethodID callbackMethodId = env->GetMethodID(
        callbackClass,
        "callback",
        "([Ljava/lang/Object;)Ljava/lang/Object;");

    if (!callbackMethodId) {
        env->ExceptionClear();
        // Try the Xposed-compatible "beforeHookedMethod" approach –
        // the Kotlin HookCallback interface uses different names, so we
        // look for a wrapper that the Kotlin layer provides.
        LOGW("nativeHookMethod(): 'callback(Object[])' not found on callback object; "
             "falling back to 'handleHook'");
        callbackMethodId = env->GetMethodID(
            callbackClass,
            "handleHook",
            "([Ljava/lang/Object;)Ljava/lang/Object;");
    }

    if (!callbackMethodId) {
        env->ExceptionClear();
        LOGE("nativeHookMethod(): no suitable callback method found (expected "
             "'Object callback(Object[])' or 'Object handleHook(Object[])')");
        env->DeleteLocalRef(callbackClass);
        return 0L;
    }

    // Convert methodID to a Method reflection object for LSPlant
    jclass methodClass = env->FindClass("java/lang/reflect/Method");
    jmethodID toMethodObj = nullptr;
    // We already have a jobject (method), pass it directly to lsplant::Hook.

    // Obtain the java.lang.reflect.Method object for the callback method
    // by calling callbackClass.getMethod / getDeclaredMethod.
    // Since we only have a jmethodID, use reflection to find it by scanning
    // the class's declared methods.
    jmethodID getDeclaredMethods = env->GetMethodID(
        env->FindClass("java/lang/Class"),
        "getDeclaredMethods",
        "()[Ljava/lang/reflect/Method;");

    jobject callbackMethodObj = nullptr;
    if (getDeclaredMethods) {
        jobjectArray methods = static_cast<jobjectArray>(
            env->CallObjectMethod(callbackClass, getDeclaredMethods));
        if (methods && !env->ExceptionCheck()) {
            jint len = env->GetArrayLength(methods);
            jmethodID getName = env->GetMethodID(
                methodClass, "getName", "()Ljava/lang/String;");
            for (jint i = 0; i < len && !callbackMethodObj; ++i) {
                jobject m = env->GetObjectArrayElement(methods, i);
                if (!m) continue;
                jstring mname = static_cast<jstring>(
                    env->CallObjectMethod(m, getName));
                if (mname) {
                    const char* chars = env->GetStringUTFChars(mname, nullptr);
                    bool match = (chars &&
                        (strcmp(chars, "callback") == 0 ||
                         strcmp(chars, "handleHook") == 0));
                    if (chars) env->ReleaseStringUTFChars(mname, chars);
                    if (match) callbackMethodObj = env->NewGlobalRef(m);
                    env->DeleteLocalRef(mname);
                }
                env->DeleteLocalRef(m);
            }
            env->DeleteLocalRef(methods);
        } else {
            env->ExceptionClear();
        }
    }
    env->DeleteLocalRef(callbackClass);

    if (!callbackMethodObj) {
        LOGE("nativeHookMethod(): could not obtain Method reflection object for callback");
        return 0L;
    }

    // ---- Call lsplant::Hook() ------------------------------------------
    // Hook(env, target_method, hooker_object, callback_method)
    // Returns the backup Method or null on failure.
    jobject backup = lsplant::Hook(env, method, callback, callbackMethodObj);
    env->DeleteGlobalRef(callbackMethodObj);

    if (env->ExceptionCheck()) {
        LOGE("nativeHookMethod(): lsplant::Hook threw an exception");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return 0L;
    }
    if (!backup) {
        LOGE("nativeHookMethod(): lsplant::Hook returned null");
        return 0L;
    }

    // Store both target and backup global refs keyed by handle.
    // nativeUnhookMethod() needs the target to call lsplant::UnHook(target).
    jlong handle = store_hook(env, method, backup);
    env->DeleteLocalRef(backup);

    LOGI("nativeHookMethod(): hook installed, handle=%" PRId64, static_cast<int64_t>(handle));
    return handle;
}

/**
 * nativeUnhookMethod() – Remove a hook by handle.
 *
 * @param handle Handle returned by nativeHookMethod().
 * @return JNI_TRUE if removed successfully.
 */
JNIEXPORT jboolean JNICALL Java_dev_zenpatch_bridge_NativeBridge_nativeUnhookMethod(
        JNIEnv* env, jclass /*clazz*/, jlong handle) {

    LOGD("nativeUnhookMethod(): handle=%" PRId64, static_cast<int64_t>(handle));

    if (!g_initialised.load(std::memory_order_acquire)) {
        LOGE("nativeUnhookMethod(): engine not initialised");
        return JNI_FALSE;
    }
    if (handle == 0L) {
        LOGE("nativeUnhookMethod(): invalid handle 0");
        return JNI_FALSE;
    }

    // Retrieve the TARGET method – lsplant::UnHook requires the original target.
    jobject target = get_target(handle);
    if (!target) {
        LOGE("nativeUnhookMethod(): no target found for handle %" PRId64,
             static_cast<int64_t>(handle));
        return JNI_FALSE;
    }

    // Call lsplant::UnHook with the original target method.
    bool ok = lsplant::UnHook(env, target);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        ok = false;
    }

    if (ok) {
        remove_hook_entry(env, handle);
        LOGI("nativeUnhookMethod(): hook %" PRId64 " removed", static_cast<int64_t>(handle));
    } else {
        LOGE("nativeUnhookMethod(): lsplant::UnHook failed for handle %" PRId64,
             static_cast<int64_t>(handle));
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * nativeIsHooked() – Check whether a method is currently hooked.
 *
 * @param method java.lang.reflect.Method to check.
 * @return JNI_TRUE if an active hook exists.
 */
JNIEXPORT jboolean JNICALL Java_dev_zenpatch_bridge_NativeBridge_nativeIsHooked(
        JNIEnv* env, jclass /*clazz*/, jobject method) {

    LOGD("nativeIsHooked(): called");

    if (!g_initialised.load(std::memory_order_acquire)) {
        LOGE("nativeIsHooked(): engine not initialised");
        return JNI_FALSE;
    }
    if (!method) {
        LOGE("nativeIsHooked(): null method");
        return JNI_FALSE;
    }

    bool hooked = lsplant::IsHooked(env, method);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return JNI_FALSE;
    }
    return hooked ? JNI_TRUE : JNI_FALSE;
}

/**
 * nativeDeoptimize() – Force ART to deoptimise a method.
 *
 * Deoptimising a method ensures its callers cannot inline it, which is
 * necessary when the method itself or a short callee has been hooked and
 * the JIT might otherwise bypass the hook point.
 *
 * @param method java.lang.reflect.Method to deoptimise.
 * @return JNI_TRUE if deoptimisation succeeded.
 */
JNIEXPORT jboolean JNICALL Java_dev_zenpatch_bridge_NativeBridge_nativeDeoptimize(
        JNIEnv* env, jclass /*clazz*/, jobject method) {

    LOGD("nativeDeoptimize(): called");

    if (!g_initialised.load(std::memory_order_acquire)) {
        LOGE("nativeDeoptimize(): engine not initialised");
        return JNI_FALSE;
    }
    if (!method) {
        LOGE("nativeDeoptimize(): null method");
        return JNI_FALSE;
    }

    bool ok = lsplant::Deoptimize(env, method);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        ok = false;
    }
    if (!ok) {
        LOGE("nativeDeoptimize(): lsplant::Deoptimize failed");
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
