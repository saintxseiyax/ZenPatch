# ZenPatch

[![Build](https://github.com/zenpatch/ZenPatch/actions/workflows/build.yml/badge.svg)](https://github.com/zenpatch/ZenPatch/actions)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL%203.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-12--16-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-purple.svg)](https://kotlinlang.org)

**ZenPatch** is a modern, non-root Xposed framework alternative for Android 12–16 (API 31–36). It patches APKs to inject the ZenPatch runtime, which loads Xposed modules using LSPlant as the hooking engine — no root, no Magisk required.

## Features

- **No root required** — works on stock Android 12–16
- **LSPlant 7.0.0** hooking engine (ART method hooks)
- **Full Xposed API compatibility** — existing modules work unchanged
- **Signature spoofing** — apps see their original signature
- **Split APK support** — handles app bundles
- **16KB page alignment** — Android 16 page size compliance
- **Privilege stack**: Dhizuku → Shizuku → Standard installer
- **Material 3 UI** with dynamic colors (Android 12+)
- **CLI tool** for automation and scripting

## Architecture

```
┌─────────────────────────────────────────────────┐
│              ZenPatch Manager App               │
│   Material 3 UI  │  Patch Wizard  │  Settings   │
├──────────────────┴────────────────┴─────────────┤
│                Patcher Engine                   │
│  Analyze → Merge → DEX Inject → Native Inject  │
│  → Manifest Patch → Sign → Install              │
├─────────────────────────────────────────────────┤
│           Runtime (injected into APK)           │
│  ZenPatchAppProxy → LSPlant → XposedBridge     │
│  → SignatureSpoof → ModuleLoader               │
├───────────────────┬─────────────────────────────┤
│  :xposed-api      │  :bridge (C++/JNI/LSPlant)  │
│  XposedBridge     │  zenpatch_bridge.so         │
│  XposedHelpers    │  LSPlant::Hook/UnHook       │
│  XSharedPrefs     │  HiddenApiBypass            │
└───────────────────┴─────────────────────────────┘
```

## Modules

| Module | Description |
|--------|-------------|
| `:app` | Manager app (Compose + Material 3) |
| `:patcher` | APK patching engine library |
| `:runtime` | Runtime injected into patched APKs |
| `:xposed-api` | Xposed API compatibility layer |
| `:bridge` | Native C++/JNI LSPlant integration |
| `:cli` | Command-line patcher tool |
| `:privilege` | Install privilege providers |

## Quick Start

### Manager App

1. Install `ZenPatch-release.apk`
2. Open ZenPatch → tap **+** (Patch App)
3. Select the APK to patch
4. Select Xposed modules to activate
5. Configure options → tap **Start Patching**
6. Install the patched APK

### CLI Tool

```bash
# Patch an APK
java -jar zenpatch.jar patch app.apk --modules module1.apk,module2.apk --out patched.apk

# Analyze an APK
java -jar zenpatch.jar analyze app.apk

# Verify a patched APK
java -jar zenpatch.jar verify patched.apk

# List available modules
java -jar zenpatch.jar list-modules ./modules/
```

## Building

Requirements:
- JDK 17
- Android SDK (API 36)
- NDK 27.0.12077973
- CMake 3.22.1+

```bash
git clone https://github.com/zenpatch/ZenPatch.git
cd ZenPatch
./gradlew assembleDebug    # Debug build
./gradlew assembleRelease  # Release build
./gradlew :cli:jar         # CLI JAR
./gradlew test             # Unit tests
```

## Writing Xposed Modules

ZenPatch is fully compatible with the standard Xposed API:

```kotlin
// In your module APK: assets/xposed_init
// dev.example.mymodule.MyHook

class MyHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.target.app") return

        XposedHelpers.findAndHookMethod(
            "com.target.app.SomeClass",
            lpparam.classLoader,
            "someMethod",
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[0] = "modified_value"
                }
            }
        )
    }
}
```

### Module Requirements

1. Declare in `AndroidManifest.xml`:
   ```xml
   <meta-data android:name="xposedmodule" android:value="true" />
   <meta-data android:name="xposeddescription" android:value="My module description" />
   <meta-data android:name="xposedminversion" android:value="93" />
   ```

2. List entry point classes in `assets/xposed_init`:
   ```
   dev.example.mymodule.MyHook
   ```

3. Depend on `xposed-api` (provided by ZenPatch):
   ```gradle
   compileOnly "de.robv.android.xposed:api:82"
   // or from ZenPatch's local module:
   compileOnly project(":xposed-api")
   ```

## Security

- **ZIP-slip prevention** on all APK operations
- **Path traversal prevention** in module loading
- **ConfigProvider auth** — only Manager UID can write config
- **Shizuku temp files** are not world-readable
- **Safer Intents** compliance (Android 16)
- **Intent redirection protection** via `removeLaunchSecurityProtection()`

See [docs/security-audit.md](docs/security-audit.md) for full audit report.

## Limitations

- **No root** means no Zygote-level hooks (`IXposedHookZygoteInit` throws `UnsupportedOperationException`)
- **No resource injection** (too fragile and version-specific)
- **Play Integrity** hardware attestation cannot be bypassed on non-root
- Modules requiring system-level access won't work

## Compatibility

| Android Version | API Level | Status |
|----------------|-----------|--------|
| Android 12 | 31, 32 | ✅ Supported |
| Android 13 | 33 | ✅ Supported |
| Android 14 | 34 | ✅ Supported |
| Android 15 | 35 | ✅ Supported |
| Android 16 | 36 | ✅ Supported (16KB page aligned) |

## License

GPL-3.0 — see [LICENSE](LICENSE).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).
