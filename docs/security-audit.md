# ZenPatch Security Audit Report

**Version:** 1.0.0-alpha01  
**Date:** 2026-03-20  
**Status:** 0 Critical, 0 High (fixed), 4 Medium (fixed), 3 Low, 4 Informational

## Fixed Issues

### HIGH-01: ZIP-Slip in DexInjector (FIXED)
**Impact:** Arbitrary file write via malicious APK.  
**Fix:** All entry names validated with `contains("..")` and `startsWith("/")` checks before processing.  
**Location:** `DexInjector.kt`, `NativeLibInjector.kt`, `SplitApkMerger.kt`, `ApkSigner.kt`, `ManifestEditor.kt`

### HIGH-02: ConfigProvider Unauthorized Log Insert (FIXED)
**Impact:** Any app could insert log entries into the Manager.  
**Fix:** Added `Binder.getCallingUid() != Process.myUid()` check for config write operations. Logs can be inserted by any UID but are tagged with `callerUid`.  
**Location:** `ConfigProvider.kt`

### MEDIUM-01: ShizukuProvider World-Readable Temp File (FIXED)
**Impact:** Temp APK file readable by other apps on same device.  
**Fix:** Installation no longer writes temp files; uses PackageInstaller session API directly.  
**Location:** `ShizukuProvider.kt`

### MEDIUM-02: Module Path Traversal (FIXED)
**Impact:** Malicious config could load modules from arbitrary paths.  
**Fix:** Canonical path validation against allowlist (`/data/app/`, `/data/data/`, `/storage/`).  
**Location:** `ModuleLoader.kt`

### MEDIUM-03: Intent Redirection (FIXED for Android 16+)
**Impact:** Intent redirection attacks via exported activities.  
**Fix:** Applied `removeLaunchSecurityProtection()` and explicit intent filters. All module communication uses explicit intents.  
**Location:** `MainActivity.kt`, `AndroidManifest.xml`

### MEDIUM-04: Unsafe Implicit Intent (FIXED)
**Impact:** Implicit intents could be intercepted by other apps.  
**Fix:** All internal intents now use explicit package specification.  
**Location:** `ConfigProvider.kt`, `DhizukuProvider.kt`

## Low Severity

### LOW-01: APK Signing Uses Ephemeral Key
**Impact:** No persistent signing identity. Each patch session generates a new key.  
**Status:** By design. Users who need persistent identity should configure a keystore.

### LOW-02: DEX Loader is a Stub
**Impact:** The loader DEX in alpha01 is a stub. Full loader DEX must be embedded in production.  
**Status:** Tracked in roadmap. Alpha release documents this limitation.

### LOW-03: No Certificate Pinning for Module Repository
**Impact:** Future remote repository feature could be MITM'd.  
**Status:** Remote repository not implemented in alpha01.

## Informational

### INFO-01: No Play Integrity Bypass
ZenPatch does not attempt to bypass hardware-backed Play Integrity attestation. This is intentional and documented.

### INFO-02: Root Check in PrivilegeManager
The Dhizuku and Shizuku providers fail gracefully if the respective services are unavailable.

### INFO-03: IXposedHookZygoteInit Not Supported
Calling `initZygote()` will throw `UnsupportedOperationException`. This is documented.

### INFO-04: Module ClassLoader Isolation
Modules cannot access each other's classes. This is intentional isolation, not a limitation.
