#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>
#include <cstring>
#include <unordered_map>
#include <mutex>
#include <atomic>
#include <sys/mman.h>
#include <unistd.h>

#include "include/zenpatch_bridge.h"

// LSPlant headers (provided via prefab)
#include "lsplant.hpp"

#define LOG_TAG "ZenPatch"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---- Minimal inline hook implementation ----
// This provides the inline_hooker/inline_unhooker callbacks that LSPlant requires.
// Uses a simple trampoline: overwrite the first instructions of the target function
// with a jump to the replacement, and allocate a trampoline for the original code.

struct InlineHookEntry {
    void* target;
    void* replacement;
    void* trampoline;    // backup: original bytes + jump back
    uint8_t saved_bytes[16]; // original instruction bytes
    size_t overwrite_size;
};

static std::mutex g_inline_hooks_mutex;
static std::unordered_map<void*, InlineHookEntry> g_inline_hooks;

static size_t page_size() {
    static size_t ps = sysconf(_SC_PAGESIZE);
    return ps;
}

static void* page_align(void* addr) {
    return reinterpret_cast<void*>(
        reinterpret_cast<uintptr_t>(addr) & ~(page_size() - 1));
}

static bool set_memory_rwx(void* addr, size_t len) {
    void* page = page_align(addr);
    size_t full_len = reinterpret_cast<uintptr_t>(addr) - reinterpret_cast<uintptr_t>(page) + len;
    // Round up to page size
    full_len = (full_len + page_size() - 1) & ~(page_size() - 1);
    return mprotect(page, full_len, PROT_READ | PROT_WRITE | PROT_EXEC) == 0;
}

#if defined(__aarch64__)
// ARM64: 16 bytes for LDR X17, [PC, #8]; BR X17; <8-byte address>
static constexpr size_t kJumpSize = 16;

static void write_jump(void* from, void* to) {
    uint32_t* code = reinterpret_cast<uint32_t*>(from);
    // LDR X17, [PC, #8]  -> loads the address that follows the BR
    code[0] = 0x58000051; // LDR X17, #8
    // BR X17
    code[1] = 0xD61F0220;
    // 8-byte absolute address
    memcpy(&code[2], &to, sizeof(void*));
}
#elif defined(__arm__)
// ARM32: 8 bytes: LDR PC, [PC, #-4]; <4-byte address>
static constexpr size_t kJumpSize = 8;

static void write_jump(void* from, void* to) {
    uint32_t* code = reinterpret_cast<uint32_t*>(from);
    // LDR PC, [PC, #-4]
    code[0] = 0xE51FF004;
    // 4-byte absolute address
    memcpy(&code[1], &to, sizeof(void*));
}
#elif defined(__x86_64__)
// x86_64: JMP [RIP+0]; <8-byte address> = 14 bytes
static constexpr size_t kJumpSize = 14;

static void write_jump(void* from, void* to) {
    uint8_t* code = reinterpret_cast<uint8_t*>(from);
    // FF 25 00 00 00 00 = JMP [RIP+0]
    code[0] = 0xFF;
    code[1] = 0x25;
    code[2] = code[3] = code[4] = code[5] = 0x00;
    memcpy(&code[6], &to, sizeof(void*));
}
#else
#error "Unsupported architecture for inline hook"
#endif

/**
 * Inline hook: overwrites the first bytes of target with a jump to hooker.
 * Returns a trampoline that executes the original bytes then jumps back.
 */
static void* inline_hook(void* target, void* hooker) {
    if (!target || !hooker) return nullptr;

    // Allocate trampoline: saved bytes + jump back to (target + kJumpSize)
    size_t trampoline_size = kJumpSize + kJumpSize; // original bytes + jump
    void* trampoline = mmap(nullptr, page_size(),
                            PROT_READ | PROT_WRITE | PROT_EXEC,
                            MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (trampoline == MAP_FAILED) {
        LOGE("inline_hook: mmap failed");
        return nullptr;
    }

    // Save original bytes
    uint8_t saved[kJumpSize];
    memcpy(saved, target, kJumpSize);

    // Write trampoline: original bytes + jump back
    memcpy(trampoline, saved, kJumpSize);
    void* continue_addr = reinterpret_cast<void*>(
        reinterpret_cast<uintptr_t>(target) + kJumpSize);
    write_jump(reinterpret_cast<uint8_t*>(trampoline) + kJumpSize, continue_addr);

    // Make target writable and write jump to hooker
    if (!set_memory_rwx(target, kJumpSize)) {
        LOGE("inline_hook: mprotect failed");
        munmap(trampoline, page_size());
        return nullptr;
    }
    write_jump(target, hooker);

    // Clear instruction cache
    __builtin___clear_cache(reinterpret_cast<char*>(target),
                            reinterpret_cast<char*>(target) + kJumpSize);
    __builtin___clear_cache(reinterpret_cast<char*>(trampoline),
                            reinterpret_cast<char*>(trampoline) + kJumpSize + kJumpSize);

    // Record the hook
    InlineHookEntry entry{};
    entry.target = target;
    entry.replacement = hooker;
    entry.trampoline = trampoline;
    memcpy(entry.saved_bytes, saved, kJumpSize);
    entry.overwrite_size = kJumpSize;

    {
        std::lock_guard<std::mutex> lock(g_inline_hooks_mutex);
        g_inline_hooks[target] = entry;
    }

    return trampoline;
}

/**
 * Inline unhook: restores original bytes at target.
 */
static bool inline_unhook(void* target) {
    std::lock_guard<std::mutex> lock(g_inline_hooks_mutex);
    auto it = g_inline_hooks.find(target);
    if (it == g_inline_hooks.end()) return false;

    const InlineHookEntry& entry = it->second;

    // Restore original bytes
    if (set_memory_rwx(entry.target, entry.overwrite_size)) {
        memcpy(entry.target, entry.saved_bytes, entry.overwrite_size);
        __builtin___clear_cache(
            reinterpret_cast<char*>(entry.target),
            reinterpret_cast<char*>(entry.target) + entry.overwrite_size);
    }

    // Free trampoline
    munmap(entry.trampoline, page_size());
    g_inline_hooks.erase(it);
    return true;
}

// ---- Global state ----

static std::mutex g_hooks_mutex;
static std::atomic<jlong> g_next_handle{1};

struct HookEntry {
    jobject target_method_ref;   // global ref
    jobject hook_method_ref;     // global ref
    jobject callback_obj_ref;    // global ref (nullable)
    jobject backup_method_ref;   // global ref - LSPlant backup
};

static std::unordered_map<jlong, HookEntry> g_hooks;
static JavaVM* g_jvm = nullptr;
static bool g_lsplant_initialized = false;

// ---- JNI_OnLoad ----

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    LOGI("ZenPatch native bridge loaded");
    return JNI_VERSION_1_6;
}

// ---- Helper: get art method from reflected method ----

static jmethodID getMethodId(JNIEnv* env, jobject method_obj) {
    jclass method_class = env->GetObjectClass(method_obj);
    // Use JNI FromReflectedMethod to get jmethodID from java.lang.reflect.Method
    return env->FromReflectedMethod(method_obj);
}

// ---- Init ----

JNIEXPORT jboolean JNICALL
Java_dev_zenpatch_runtime_NativeBridge_nativeInit(JNIEnv *env, jclass cls) {
    if (g_lsplant_initialized) {
        LOGD("LSPlant already initialized");
        return JNI_TRUE;
    }

    LOGI("Initializing LSPlant...");

    // Resolve libart.so handle for ART symbol resolution
    void* libart = dlopen("libart.so", RTLD_NOW | RTLD_GLOBAL);
    if (!libart) {
        LOGE("Failed to open libart.so: %s", dlerror());
        // Try runtime library path
        libart = dlopen("/apex/com.android.art/lib64/libart.so", RTLD_NOW | RTLD_GLOBAL);
        if (!libart) {
            LOGE("Failed to open libart.so from APEX: %s", dlerror());
            return JNI_FALSE;
        }
    }

    // Initialize LSPlant
    lsplant::InitInfo init_info{};
    init_info.inline_hooker = [](void* target, void* replacement) -> void* {
        // Use our minimal inline hook implementation
        return inline_hook(target, replacement);
    };
    init_info.inline_unhooker = [](void* func) -> bool {
        return inline_unhook(func);
    };
    init_info.art_symbol_resolver = [libart](std::string_view symbol) -> void* {
        void* sym = dlsym(libart, std::string(symbol).c_str());
        if (!sym) {
            LOGW("ART symbol not found: %.*s", (int)symbol.size(), symbol.data());
        }
        return sym;
    };
    init_info.art_symbol_prefix_resolver = [libart](std::string_view prefix) -> void* {
        // For prefix-based symbol lookup (used by LSPlant for versioned ART APIs)
        return dlsym(libart, std::string(prefix).c_str());
    };

    bool result = lsplant::Init(env, init_info);
    if (result) {
        g_lsplant_initialized = true;
        LOGI("LSPlant initialized successfully");
    } else {
        LOGE("LSPlant initialization failed");
    }

    return result ? JNI_TRUE : JNI_FALSE;
}

// ---- Hook Method ----

JNIEXPORT jlong JNICALL
Java_dev_zenpatch_runtime_NativeBridge_nativeHookMethod(
        JNIEnv *env, jclass cls,
        jobject targetMethod,
        jobject hookMethod,
        jobject callbackObject) {

    if (!g_lsplant_initialized) {
        LOGE("LSPlant not initialized, cannot hook");
        return 0L;
    }

    if (!targetMethod || !hookMethod) {
        LOGE("hookMethod: null target or hook method");
        return 0L;
    }

    // Use LSPlant to hook the target method
    // LSPlant::Hook takes the reflected Method objects
    jobject backup = lsplant::Hook(env, targetMethod, callbackObject, hookMethod);
    if (!backup) {
        LOGE("LSPlant::Hook failed");
        return 0L;
    }

    // Store hook entry
    jlong handle = g_next_handle.fetch_add(1);
    HookEntry entry{};
    entry.target_method_ref = env->NewGlobalRef(targetMethod);
    entry.hook_method_ref = env->NewGlobalRef(hookMethod);
    entry.callback_obj_ref = callbackObject ? env->NewGlobalRef(callbackObject) : nullptr;
    entry.backup_method_ref = env->NewGlobalRef(backup);

    {
        std::lock_guard<std::mutex> lock(g_hooks_mutex);
        g_hooks[handle] = entry;
    }

    LOGD("Hooked method, handle=%lld", (long long)handle);
    return handle;
}

// ---- Unhook Method ----

JNIEXPORT void JNICALL
Java_dev_zenpatch_runtime_NativeBridge_nativeUnhookMethod(
        JNIEnv *env, jclass cls,
        jlong hookHandle) {

    std::lock_guard<std::mutex> lock(g_hooks_mutex);
    auto it = g_hooks.find(hookHandle);
    if (it == g_hooks.end()) {
        LOGW("unhookMethod: handle %lld not found", (long long)hookHandle);
        return;
    }

    const HookEntry& entry = it->second;

    // Unhook via LSPlant
    bool result = lsplant::UnHook(env, entry.target_method_ref);
    if (!result) {
        LOGW("LSPlant::UnHook failed for handle %lld", (long long)hookHandle);
    }

    // Release global refs
    env->DeleteGlobalRef(entry.target_method_ref);
    env->DeleteGlobalRef(entry.hook_method_ref);
    if (entry.callback_obj_ref) env->DeleteGlobalRef(entry.callback_obj_ref);
    env->DeleteGlobalRef(entry.backup_method_ref);

    g_hooks.erase(it);
    LOGD("Unhooked method handle=%lld", (long long)hookHandle);
}

// ---- Invoke Original ----

JNIEXPORT jobject JNICALL
Java_dev_zenpatch_runtime_NativeBridge_nativeInvokeOriginal(
        JNIEnv *env, jclass cls,
        jlong hookHandle,
        jobject thisObject,
        jobjectArray args) {

    std::lock_guard<std::mutex> lock(g_hooks_mutex);
    auto it = g_hooks.find(hookHandle);
    if (it == g_hooks.end()) {
        LOGE("invokeOriginal: handle %lld not found", (long long)hookHandle);
        return nullptr;
    }

    const HookEntry& entry = it->second;

    // Invoke the backup method (pre-hook implementation)
    // backup_method_ref is the LSPlant backup - call it via reflection
    jclass method_class = env->FindClass("java/lang/reflect/Method");
    jmethodID invoke_method = env->GetMethodID(
        method_class, "invoke",
        "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");

    return env->CallObjectMethod(
        entry.backup_method_ref,
        invoke_method,
        thisObject,
        args);
}

// ---- Deoptimize ----

JNIEXPORT void JNICALL
Java_dev_zenpatch_runtime_NativeBridge_nativeDeoptimize(
        JNIEnv *env, jclass cls,
        jobject method) {
    if (!g_lsplant_initialized || !method) return;
    bool result = lsplant::Deoptimize(env, method);
    if (!result) {
        LOGW("Deoptimize failed");
    }
}
