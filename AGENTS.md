# Repository Guidelines

## Current Repository Snapshot

- Business protocol version: `3`; transport protocol version: `1`.
- Runtime flow: `app` owns `MainActivity`/`MainScreen`, the foreground `InputBridgeService`, and the app-specific `InputBridgeServer`; `core:data` owns `TextRepository`, `core:datastore` owns the DataStore implementation, and `core:designsystem` owns the Compose theme. `android-studio-plugin` owns `InputBridgeToolWindowFactory`, `InputBridgePanel`, `BridgeConnectionCoordinator`, ADB, and the TCP client.
- `protocol` owns `ProtocolModels.kt`, `ProtocolConstants.kt`, and serialization configuration shared by both products.
- `build-logic/convention` owns the shared Android, Kotlin/JVM, and Koin Convention Plugins.
- The default plugin target is IntelliJ IDEA `2026.1.1` with Android plugin `261.23567.138`; use Java 21 and the checked-in Gradle wrapper.

## Project Structure & Module Organization

This repository uses one root Gradle project with two product modules, one shared protocol module, three Android core modules, and four pure Kotlin/JVM transport modules:

- `app/`: Kotlin Android application, with production code under `app/src/main`, unit tests under `app/src/test`, and Android resources under `app/src/main/res`.
- `core/datastore/`: Android library that hides AndroidX DataStore behind the `TextDataSource` API.
- `core/data/`: Android library that owns `TextRepository` and depends on `core/datastore`.
- `core/designsystem/`: Android Compose library that owns the shared `InputBridgeTheme`.
- `core/framing/`: plain Kotlin/JVM length-prefixed framing module.
- `core/crypto/`: plain Kotlin/JVM transport handshake and encryption module.
- `core/connection-client/`: plain Kotlin/JVM TCP client connection module.
- `core/connection-server/`: plain Kotlin/JVM TCP server connection module.
- `protocol/`: plain Kotlin/JVM TCP protocol module, with shared wire models under `protocol/src/main/kotlin` and serialization tests under `protocol/src/test/kotlin`.
- `build-logic/convention/`: included Gradle build that provides shared Convention Plugins.
- `android-studio-plugin/`: Kotlin IntelliJ Platform plugin module targeting Android Studio and IntelliJ IDEA, with code under `src/main/kotlin`, tests under `src/test/kotlin`, and plugin metadata under `src/main/resources/META-INF`.
- `docs/`: protocol and commit conventions.
- `.github/workflows/`: pull request/main CI and push-tag release workflow using `bridgeVersion` for artifacts.
- `.worktrees/`: local isolated Git worktrees used for feature implementation; its contents are not committed.
- `CHANGELOG.md`: release history; entries describe the behavior of the corresponding version.

Keep the Android App and plugin independently buildable within the same root Gradle project. Keep `protocol/` limited to versioned wire models and serialization contracts. Keep DataStore implementation behind `core/datastore` and expose repository APIs through `core/data`.

## Documentation Authority

Use the documents in this order when information differs:

1. Source code and tests: actual implementation behavior.
2. `docs/tcp-protocol.md`: cross-module wire compatibility.
3. `README.md`: onboarding and user-facing overview.
4. `AGENTS.md`: contribution workflow and AI-specific guardrails.

Planning history is not an automatic task queue; derive current work from the requested change, open issues, and the current codebase.

## Worktree Workflow

Create temporary or isolated worktrees under `.worktrees/` using a descriptive name, for example:

```bash
git worktree add .worktrees/feature-name -b agent/feature-name
```

Keep implementation changes inside the selected worktree and remove it after the branch has been integrated. Do not commit files generated under `.worktrees/`.

## Build, Test, and Development Commands

Run commands from the repository root:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :protocol:test
./gradlew :core:framing:test :core:crypto:test :core:connection-client:test :core:connection-server:test
./gradlew :build-logic:convention:validatePlugins
./gradlew :android-studio-plugin:buildPlugin
./gradlew :android-studio-plugin:test
./gradlew :android-studio-plugin:verifyPlugin
./gradlew detektAll
./gradlew -PdetektAutoCorrect=false detektAll
```

Detekt is configured by the convention plugins and uses `config/detekt/detekt.yml`. It analyzes Kotlin
sources in `main`, `test`, and `androidTest`; `build-logic/convention` itself is intentionally excluded.
Local `detektAll` runs lightweight main/test/androidTest source checks with auto-correction enabled by default
and may modify source files. Release verification runs the type-resolution `detektRelease` checks, which depend
on the corresponding release Kotlin compilation. CI and Release
must pass `-PdetektAutoCorrect=false`, so findings fail the build instead of changing files. Do not add a
Detekt baseline, ktlint rules, or Compose-specific rules without an explicit scope decision.

Use `adb forward tcp:18080 tcp:18080` for manual end-to-end checks. TCP and ADB operations must run off the IntelliJ EDT and use bounded timeouts.

Plugin tasks default to IntelliJ IDEA 2026.1.1 with Android plugin `261.23567.138`.

## Coding Style & Naming Conventions

Use Kotlin official style with four-space indentation. Use `PascalCase` for classes and composables, `camelCase` for functions and properties, and `UPPER_SNAKE_CASE` only for constants. Keep files focused by responsibility and prefer explicit interfaces between storage, server, ADB, TCP, clipboard, and UI layers. Follow Compose conventions in the Android App and IntelliJ/Swing threading rules in the plugin.

## Testing Guidelines

Test version changes, persistence, UTF-8/multiline text, TCP messages, handshake failures, ADB parsing, version conflicts, clipboard failure handling, and Copy-before-Clear ordering. Name tests after observable behavior, such as `clearWithStaleVersionReturnsConflict`. Add or update tests with every behavior change; no global coverage threshold is defined yet.

Before a PR, run the full matrix: Android lint and unit tests, core JVM tests, Protocol tests, Plugin tests, `buildPlugin`, and `verifyPlugin`.

## Commit & Pull Request Guidelines

Use the repository convention in [`docs/git-commit-convention.md`](docs/git-commit-convention.md): English by default and messages such as `feat(app): persist the current text state` or `fix(plugin): ignore stale socket callbacks`. The existing Git history and this documented convention are authoritative.

Keep commits focused. Pull requests should describe scope and validation commands, link relevant issues when available, include UI screenshots for Tool Window changes, and call out protocol or compatibility changes. Do not include generated build output or unrelated cleanup.

## Architecture Constraints

The Android server listens only on `127.0.0.1:18080` and exposes the versioned TCP protocol described in `docs/tcp-protocol.md`; it has no HTTP path. The plugin communicates through ADB forwarding and a persistent TCP session. TCP connect timeout is 2 seconds and command timeout is 4 seconds; ADB commands use a 5-second timeout. Do not add settings persistence, global hotkeys, simulated input, automatic paste, or system clipboard reads.
