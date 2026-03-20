# Changelog

All notable changes to ZenPatch are documented in this file.

## [1.0.0-alpha01] - 2026-03-20

### Added
- Initial alpha release
- LSPlant 7.0.0 integration for ART method hooking
- Full Xposed API compatibility layer (XposedBridge, XposedHelpers, XC_MethodHook, XSharedPreferences)
- APK patching pipeline: analyze → merge splits → inject DEX → inject native → patch manifest → sign
- ZenPatchAppProxy runtime bootstrap with graceful degradation
- Signature spoofing via PackageManager hook
- Module loading with isolated ClassLoaders per module
- Hidden API bypass (setHiddenApiExemptions)
- Material 3 Manager App with Jetpack Compose
- Patch Wizard UI (4-step: select APK → modules → options → patch/install)
- CLI tool: `patch`, `analyze`, `verify`, `list-modules` commands
- Privilege providers: Dhizuku → Shizuku → Standard installer auto-selection
- ConfigProvider IPC (Manager ↔ patched apps)
- 16KB page alignment for Android 16 compatibility
- Android 16 Safer Intents compliance
- Android 16 Intent Redirection Protection

### Security
- ZIP-slip prevention in DexInjector, NativeLibInjector, ManifestEditor, ApkSigner
- ConfigProvider log insert auth-protected (UID check)
- ShizukuProvider temp files not world-readable
- ModuleLoader APK path validation against path traversal

### Known Limitations
- `IXposedHookZygoteInit` not supported (non-root framework)
- No resource injection
- Play Integrity hardware attestation bypass not included
