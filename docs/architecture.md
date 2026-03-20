# ZenPatch Architecture

## Overview

ZenPatch patches APKs to inject a runtime that uses LSPlant to hook Java methods, enabling Xposed modules to run without root.

## Patching Pipeline

```
Original APK
     │
     ▼
ApkAnalyzer         ← Parse manifest, DEX list, ABI list, signature schemes, split detection
     │
     ▼
SplitApkMerger      ← Merge base.apk + split_*.apk into universal APK (if split)
     │
     ▼
DexInjector         ← Inject loader DEX as classes.dex, renumber existing DEX files
     │
     ▼
NativeLibInjector   ← Inject liblsplant.so + libzenpatch_bridge.so, 16KB-aligned
     │
     ▼
ManifestEditor      ← Replace Application class, write assets/zenpatch/config.properties sidecar
     │
     ▼
ApkSigner           ← Strip old signatures, embed original cert, sign v2+v3
     │
     ▼
Patched APK (installable)
```

## Runtime Boot Sequence

```
App starts
    │
    ▼
ZenPatchAppProxy.attachBaseContext()
    ├── NativeBridge.init()           ← dlopen libart.so, LSPlant::Init()
    ├── HiddenApiBypass.install()     ← VMRuntime.setHiddenApiExemptions(["L"])
    ├── XposedBridge.setImpl()        ← Provider-Injection-Pattern
    ├── SignatureSpoof.install()      ← Hook PackageManager.getPackageInfo()
    ├── ModuleLoader.loadModules()    ← Read config.properties, create PathClassLoaders
    ├── notifyPackageLoaded()         ← Call IXposedHookLoadPackage.handleLoadPackage()
    └── createAndAttachOriginalApp()  ← Delegate to original Application class
```

## Module Loading

Each module gets an isolated `PathClassLoader` with the app's class loader as parent:

```
App ClassLoader (parent)
    └── Module1 PathClassLoader (sees app classes + module classes)
    └── Module2 PathClassLoader (sees app classes + module classes, isolated from Module1)
```

This prevents cross-module class contamination while allowing modules to access app classes.

## Security Architecture

### ZIP-Slip Prevention
All ZIP operations canonicalize entry paths and reject entries containing `..` or absolute paths:
```kotlin
if (entryName.contains("..")) continue
if (entryName.startsWith("/")) continue
```

### ConfigProvider IPC
The ConfigProvider allows patched apps to log messages back to the Manager. Write operations are restricted to the Manager's own UID:
```kotlin
if (Binder.getCallingUid() != Process.myUid()) return null
```

### Module Path Validation
Module APK paths are validated against an allowlist of safe prefixes before loading:
```kotlin
val acceptablePrefixes = listOf("/data/app/", "/data/data/", "/storage/")
val isSafe = acceptablePrefixes.any { canonicalPath.startsWith(it) }
```

## Provider-Injection-Pattern

`XposedBridge` in `:xposed-api` defines only an interface (`XposedBridgeImpl`). The `:runtime` module provides `NativeBridgeXposedImpl` backed by LSPlant. This avoids circular module dependencies:

```
:xposed-api  ────────────────────────────────► (no deps)
:runtime     ─── depends on ───► :xposed-api
:app         ─── depends on ───► :patcher, :privilege, :xposed-api
```

## 16KB Page Alignment

Android 16 requires `.so` files to be 16KB-aligned in ZIP files. This is achieved by padding the ZIP entry's `extra` field:

```
ZIP Local Header (30 bytes) + Name (N bytes) + Extra (E bytes) → File Data
                                                     ▲
                                    E = (16384 - (30 + N) % 16384) % 16384
```

The native build system also links with `-Wl,-z,max-page-size=16384`.
