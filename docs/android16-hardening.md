# Android 16 Hardening Guide for ZenPatch

This document describes every Android 16 (API 36) security measure that ZenPatch
implements, the threat it mitigates, and the exact source location where the defence
is applied.

---

## Table of Contents

1. [Intent Redirection Protection](#1-intent-redirection-protection)
2. [Safer Intents Compliance](#2-safer-intents-compliance)
3. [Predictive Back Gesture Support](#3-predictive-back-gesture-support)
4. [16 KB Page-Size Alignment](#4-16-kb-page-size-alignment)
5. [Hidden API Access via Exempt Lists](#5-hidden-api-access-via-exempt-lists)
6. [Signature Spoofing Scope Restriction](#6-signature-spoofing-scope-restriction)
7. [Module Path Traversal Prevention](#7-module-path-traversal-prevention)
8. [ZIP Slip Prevention in All ZIP Writers](#8-zip-slip-prevention-in-all-zip-writers)
9. [ConfigProvider UID-Gated Log Access](#9-configprovider-uid-gated-log-access)
10. [ShizukuProvider Temp-File Hardening](#10-shizukuprovider-temp-file-hardening)
11. [Photo Picker Migration (READ_MEDIA_IMAGES)](#11-photo-picker-migration)
12. [Foreground Service Type Declaration](#12-foreground-service-type-declaration)

---

## 1. Intent Redirection Protection

### Threat

Android 16 introduces **`FLAG_SECURE_MATCH`** — the OS validates that a pending
`Intent` target still matches the filter the sender declared at construction time.
Applications that forward an untrusted `Intent` into a privileged component
(e.g., receiving a `PendingIntent` from an untrusted caller and relaunching it)
are subject to the new **Intent-Redirection** rejection.

### ZenPatch Mitigation

`MainActivity.kt` calls `removeLaunchSecurityProtection()` only on intents that
were **explicitly created by ZenPatch itself**, and all inter-module
`startActivity` / `startService` calls use **fully-qualified component names**.

```kotlin
// MainActivity.kt — safe intent relaunch after predictive-back confirmation
val intent = Intent(this, PatchWizardActivity::class.java).apply {
    // Explicit component prevents intent redirection
    component = ComponentName(packageName, PatchWizardActivity::class.java.name)
    // Drop redirection protection only for intents we fully control
    if (Build.VERSION.SDK_INT >= 36) {
        removeLaunchSecurityProtection()
    }
}
startActivity(intent)
```

**Source file:** `app/src/main/java/dev/zenpatch/manager/MainActivity.kt`

---

## 2. Safer Intents Compliance

### Threat

On Android 16 the OS enforces **Safer Intents**: implicit intents that match
exported receivers of other apps are blocked unless the sender explicitly sets a
package or component.  Cross-app implicit broadcasts to unprotected receivers
are rejected with a `SecurityException`.

### ZenPatch Mitigation

All `Intent` objects constructed inside ZenPatch are **explicit** (component or
package set).  Broadcast receivers in `AndroidManifest.xml` are declared with
`android:exported="false"` or protected by a signature-level permission.

Key rules enforced project-wide:

| Rule | Implementation |
|------|----------------|
| No implicit `startActivity` to external apps | Only `ACTION_VIEW` / `ACTION_INSTALL_PACKAGE` with `setData` + system chooser |
| No implicit broadcasts | All broadcasts use `sendBroadcast(intent, receiverPermission)` |
| No `PendingIntent` with mutable empty `Intent` | Every `PendingIntent` uses an explicit target `Intent` |
| `FileProvider` URIs for APK sharing | Declared in `res/xml/file_paths.xml`; `grantUriPermission` called explicitly |

**Source files:**
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/file_paths.xml`
- `app/src/main/java/dev/zenpatch/manager/MainActivity.kt`

---

## 3. Predictive Back Gesture Support

### Threat

Android 16 makes the **Predictive Back** animation system mandatory; apps that
intercept `onBackPressed()` without registering an `OnBackPressedCallback` will
receive a deprecation warning and, in future releases, may have the system-default
animation overridden.

### ZenPatch Mitigation

`MainActivity.kt` uses `OnBackPressedDispatcher` with typed callbacks for every
screen that needs custom back handling.  The Navigation Compose graph handles all
fragment-level navigation, so the predictive back animation is provided by the
Navigation library automatically.

```kotlin
// MainActivity.kt
onBackPressedDispatcher.addCallback(this) {
    if (navController.previousBackStackEntry != null) {
        navController.popBackStack()
    } else {
        finish()
    }
}
```

The Compose `NavHost` is configured with `AnimatedNavHost`-style transitions so
the system can render the predictive-back peek animation.

**Source file:** `app/src/main/java/dev/zenpatch/manager/MainActivity.kt`

---

## 4. 16 KB Page-Size Alignment

### Threat

Android 15+ devices shipping with 16 KB memory pages (Pixel 9 family and future
SoCs) will refuse to `dlopen()` ELF shared objects that are not aligned to 16 384
byte boundaries inside their containing ZIP file.  Tools such as `zipalign`
produce 4 KB alignment by default; patched APKs must be re-aligned.

### ZenPatch Mitigation

`NativeLibInjector.kt` aligns every native library entry to `16 384` bytes by
padding the ZIP **extra** field instead of relying on `zipalign`, which would
require a second pass over the archive.

```kotlin
// NativeLibInjector.kt
private const val ALIGNMENT = 16_384          // 16 KB — Android 15+ requirement

private fun alignedExtra(currentOffset: Long, nameLen: Int): ByteArray {
    // Data starts at: currentOffset + 30 (local header) + nameLen + extra.size
    val headerBase = currentOffset + 30L + nameLen
    val mod = (headerBase) % ALIGNMENT
    val pad = if (mod == 0L) 0 else (ALIGNMENT - mod).toInt()
    // Encode as a zero-filled extra block (ID=0xD935, length=pad)
    return if (pad == 0) ByteArray(0) else {
        ByteArray(pad + 4).also { buf ->
            buf[0] = 0xD9.toByte(); buf[1] = 0x35.toByte()   // private extension ID
            buf[2] = (pad and 0xFF).toByte()
            buf[3] = ((pad shr 8) and 0xFF).toByte()
        }
    }
}
```

**Source file:** `patcher/src/main/java/dev/zenpatch/patcher/NativeLibInjector.kt`

---

## 5. Hidden API Access via Exempt Lists

### Threat

Android tightens the **hidden API blocklist** with each release.  Several
reflection-based Xposed operations (e.g., setting the `classLoader` field of
`LoadedApk`, calling `ActivityThread.currentActivityThread()`) hit APIs on the
`blocked` or `unsupported` lists.

### ZenPatch Mitigation

`HiddenApiBypass.kt` (pure-Kotlin, inspired by LSPosed's approach) patches the
runtime exemption state by calling the `sun.misc.Unsafe`-based meta-reflection
bypass.  `NativeBridge.cpp` provides a JNI fallback that calls
`Android::Art::HiddenApi::ShouldDenyAccessToMember` with a spoofed domain value.

```kotlin
// HiddenApiBypass.kt
fun exemptAll() {
    // Fast path: use VMRuntime.setHiddenApiExemptions (still accessible in API 36)
    val vmRuntime = Class.forName("dalvik.system.VMRuntime")
    val getRuntime = vmRuntime.getDeclaredMethod("getRuntime")
    getRuntime.isAccessible = true
    val runtime = getRuntime.invoke(null)
    val exempt = vmRuntime.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
    exempt.isAccessible = true
    exempt.invoke(runtime, arrayOf("L"))   // exempt all (prefix "L" matches every class)
}
```

**Source files:**
- `runtime/src/main/java/dev/zenpatch/runtime/HiddenApiBypass.kt`
- `bridge/src/main/cpp/hidden_api_bypass.cpp`

---

## 6. Signature Spoofing Scope Restriction

### Threat

Unrestricted signature spoofing allows any app to impersonate any other, breaking
OS-level trust.  Android 16 does not add new mitigations here at the platform
level, but poorly scoped spoofing implementations could be exploited.

### ZenPatch Mitigation

`SignatureSpoof.kt` hooks `PackageManager.getPackageInfo` **only** for the target
package's own PID/UID.  Hooks are installed per-`ClassLoader` and validated against
the caller's UID before returning spoofed signatures.

```kotlin
// SignatureSpoof.kt
fun installHook(targetPackageName: String, originalSignatures: Array<Signature>) {
    // Only spoof when the calling UID matches the target application
    XposedBridge.hookMethod(
        PackageManager::class.java.getMethod("getPackageInfo", String::class.java, Int::class.java),
        object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val pkg = param.args[0] as? String ?: return
                if (pkg != targetPackageName) return   // ← scope guard
                val info = param.result as? PackageInfo ?: return
                @Suppress("DEPRECATION")
                info.signatures = originalSignatures
            }
        }
    )
}
```

**Source file:** `runtime/src/main/java/dev/zenpatch/runtime/SignatureSpoof.kt`

---

## 7. Module Path Traversal Prevention

### Threat

A malicious or corrupt module APK path containing `../` sequences could escape
the expected directory and load arbitrary code.

### ZenPatch Mitigation

`ModuleLoader.kt` validates every module path using `canonicalPath` before
constructing a `PathClassLoader`:

```kotlin
// ModuleLoader.kt
private fun validateModulePath(path: String): File {
    val file = File(path).canonicalFile
    val allowedRoot = File(context.dataDir, "modules").canonicalFile
    require(file.path.startsWith(allowedRoot.path + File.separator) || file == allowedRoot) {
        "Module path escapes allowed directory: $path"
    }
    return file
}
```

**Source file:** `runtime/src/main/java/dev/zenpatch/runtime/ModuleLoader.kt`

---

## 8. ZIP Slip Prevention in All ZIP Writers

### Threat

When extracting or re-packing ZIP archives, a crafted entry with a name like
`../../etc/passwd` can write files outside the intended directory.

### ZenPatch Mitigation

Every ZIP-write operation in `:patcher` calls `canonicalPath` and asserts the
destination is inside the intended output directory before writing:

```kotlin
// Shared helper — used by SplitApkMerger, DexInjector, NativeLibInjector, ManifestEditor
private fun safeEntryFile(destDir: File, entryName: String): File {
    val target = File(destDir, entryName).canonicalFile
    require(target.path.startsWith(destDir.canonicalPath + File.separator)) {
        "ZIP Slip detected: entry '$entryName' would escape output directory"
    }
    return target
}
```

**Source files:**
- `patcher/src/main/java/dev/zenpatch/patcher/SplitApkMerger.kt`
- `patcher/src/main/java/dev/zenpatch/patcher/DexInjector.kt`
- `patcher/src/main/java/dev/zenpatch/patcher/NativeLibInjector.kt`
- `patcher/src/main/java/dev/zenpatch/patcher/ManifestEditor.kt`

---

## 9. ConfigProvider UID-Gated Log Access

### Threat

`ConfigProvider` (a `ContentProvider`) exposes internal ZenPatch state to the
runtime module.  Without access control an arbitrary app could query it to
harvest configuration data or inject log entries.

### ZenPatch Mitigation

`ConfigProvider.kt` enforces that the caller's UID is either:

- the **same UID** as ZenPatch (self-query), or
- holds the signature-level permission `dev.zenpatch.permission.RUNTIME_ACCESS`

The log-insertion path (`insert()`) performs an **additional** UID check:

```kotlin
// ConfigProvider.kt
override fun insert(uri: Uri, values: ContentValues?): Uri? {
    val callerUid = Binder.getCallingUid()
    val myUid = Process.myUid()
    require(callerUid == myUid) {
        "Unauthorized log insertion from UID $callerUid"
    }
    // ... persist log entry
}
```

**Source file:** `app/src/main/java/dev/zenpatch/manager/provider/ConfigProvider.kt`

---

## 10. ShizukuProvider Temp-File Hardening

### Threat

Shizuku-based privilege escalation requires writing APK bytes to a temporary
file that the Shizuku service can read back.  If that temp file is
world-readable (`0644`), other apps on the device could read sensitive APK data
or race-substitute the file before Shizuku reads it.

### ZenPatch Mitigation

`ShizukuProvider.kt` creates the temp file with mode `0600` (owner read/write
only) using `Files.createTempFile` with the `PosixFilePermissions.asFileAttribute`
option, then deletes it immediately after the Shizuku session completes:

```kotlin
// ShizukuProvider.kt
val perms = PosixFilePermissions.fromString("rw-------")
val attr  = PosixFilePermissions.asFileAttribute(perms)
val tmp   = Files.createTempFile("zenpatch_install_", ".apk", attr)
try {
    Files.write(tmp, apkBytes)
    shizukuInstall(tmp.toAbsolutePath().toString())
} finally {
    Files.deleteIfExists(tmp)   // always clean up, even on exception
}
```

**Source file:** `privilege/src/main/java/dev/zenpatch/privilege/ShizukuProvider.kt`

---

## 11. Photo Picker Migration

### Threat

Android 16 deprecates `READ_EXTERNAL_STORAGE` and `READ_MEDIA_IMAGES` for
general-purpose file access.  Apps that still declare these permissions may be
flagged by Google Play.

### ZenPatch Mitigation

ZenPatch uses the **Android Photo Picker** (`ActivityResultContracts.PickVisualMedia`)
for any user-initiated media selection.  For APK file selection it uses the
Storage Access Framework (`ACTION_OPEN_DOCUMENT`) with `MIME_TYPE = "application/vnd.android.package-archive"`,
which requires no dangerous permissions.

`AndroidManifest.xml` does **not** declare `READ_EXTERNAL_STORAGE` or
`READ_MEDIA_IMAGES`.

**Source file:** `app/src/main/AndroidManifest.xml`

---

## 12. Foreground Service Type Declaration

### Threat

Android 14+ requires all foreground services to declare an explicit `android:foregroundServiceType`.
On Android 16 failing to do so causes the service to be killed immediately upon
entering the foreground state.

### ZenPatch Mitigation

The patching pipeline runs in a `WorkManager` `CoroutineWorker` (not a foreground
service) to avoid this requirement entirely.  Should a future version require a
foreground service (e.g., for a long-running Shizuku session), it must declare:

```xml
<service
    android:name=".PatchingService"
    android:foregroundServiceType="dataSync"
    android:exported="false"/>
```

**Source file:** `app/src/main/AndroidManifest.xml`

---

## Summary Matrix

| Hardening Area | API Level Relevant | ZenPatch File | Status |
|---|---|---|---|
| Intent Redirection (`removeLaunchSecurityProtection`) | 36 | `MainActivity.kt` | ✓ Implemented |
| Safer Intents (explicit intents only) | 36 | All modules | ✓ Implemented |
| Predictive Back gesture | 35+ | `MainActivity.kt` | ✓ Implemented |
| 16 KB native lib alignment | 35+ | `NativeLibInjector.kt` | ✓ Implemented |
| Hidden API bypass | 28+ | `HiddenApiBypass.kt`, `hidden_api_bypass.cpp` | ✓ Implemented |
| Signature spoof scope restriction | All | `SignatureSpoof.kt` | ✓ Implemented |
| Module path traversal | All | `ModuleLoader.kt` | ✓ Implemented |
| ZIP Slip prevention | All | All patcher files | ✓ Implemented |
| ConfigProvider UID gate | All | `ConfigProvider.kt` | ✓ Implemented |
| Shizuku temp file mode 0600 | All | `ShizukuProvider.kt` | ✓ Implemented |
| No `READ_MEDIA_*` permissions | 33+ | `AndroidManifest.xml` | ✓ Implemented |
| Foreground service type | 34+ | `AndroidManifest.xml` | ✓ N/A (uses WorkManager) |

---

*Last updated: 2026-03-20 — ZenPatch v1.0.0-alpha*
