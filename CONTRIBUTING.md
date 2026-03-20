# Contributing to ZenPatch

Thank you for your interest in contributing to ZenPatch!

## Development Setup

1. Fork and clone the repository
2. Install JDK 17, Android SDK (API 36), NDK 27.0.12077973
3. Open in Android Studio Ladybug (2024.2) or newer
4. Run `./gradlew assembleDebug` to verify setup

## Code Style

- Kotlin: Official Kotlin code style (`kotlin.code.style=official`)
- Java: Google Java Style Guide for the `:xposed-api` module
- C++: LLVM style for native code
- Use `./gradlew lint` to check for issues

## Making Changes

1. Create a feature branch: `git checkout -b feature/my-feature`
2. Write tests for new functionality
3. Ensure all tests pass: `./gradlew test`
4. Run lint: `./gradlew lint`
5. Submit a pull request

## Security Issues

**Please do NOT open public issues for security vulnerabilities.**
Email security@zenpatch.dev instead. See [docs/security-audit.md](docs/security-audit.md).

## Pull Request Guidelines

- Keep PRs focused (one feature/fix per PR)
- Add/update tests for all changes
- Update documentation for user-facing changes
- Reference any related issues

## Architecture Notes

- The `:xposed-api` module must remain pure Java for maximum compatibility
- The `:runtime` module must not depend on `:app` (injected independently)
- All ZIP operations must include ZIP-slip prevention
- Security fixes go in the relevant module; document in `docs/security-audit.md`
