# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
While the project remains in the v1.x preview phase, releases may introduce
breaking changes without preserving upgrade compatibility.

## [1.2.0] - 2026-07-25

### Added

- Added encrypted TCP transport with shared-secret authentication, HKDF-SHA-256 key derivation, and AES-256-GCM records.
- Added reusable framing, TCP client/server, crypto, data, DataStore, design system, and Gradle convention modules.

### Changed

- Replaced the legacy WebSocket transport with length-prefixed TCP framing over the ADB-forwarded loopback connection.
- Kept the business protocol at version `3` and introduced transport protocol version `1`.
- Centralized common Android and Kotlin/JVM build configuration in Convention Plugins.

### Compatibility

- The Android App and Android Studio plugin must be upgraded together and built with the same `inputBridgeSharedSecret` value.
- Legacy unencrypted transport is not supported. The v1.x preview line may introduce breaking changes without preserving upgrade compatibility.

## [1.1.2] - 2026-07-18

### Changed

- Renamed the product branding and package identifiers to Input Bridge.
- Changed the Android application ID, IntelliJ plugin ID, and DataStore file name. Users of earlier v1.x preview artifacts must uninstall and reinstall the App and plugin; existing persisted text is not migrated.

## [1.1.1] - 2026-07-17

### Changed

- Refreshed the Android App main screen layout.
- Limited Android text input to 8,000 code points.

### Fixed

- Registered the plugin notification group safely to prevent notification failures.

## [1.1.0] - 2026-07-17

### Added

- Added a shared, versioned WebSocket protocol for the Android App and Android Studio plugin.
- Added live text synchronization from the Android App without HTTP polling.
- Added notification click handling that opens the Android App.
- Added WebSocket lifecycle, command correlation, malformed-frame, and integration coverage.

### Changed

- Replaced the loopback HTTP transport and 300ms polling flow with a persistent WebSocket connection over ADB forwarding.
- Redesigned the Android Studio Tool Window for live synchronization and removed the obsolete Refresh flow.
- Replaced the HTTP API documentation with the WebSocket protocol specification.

## [1.0.1] - 2026-07-16

### Added

- Added coordinator-owned HTTP polling at a fixed 300ms interval for near-real-time text synchronization.
- Added an injectable polling scheduler and lifecycle tests for cancellation, failure handling, and request de-duplication.
- Added a wrapping Tool Window action layout so buttons remain visible in narrow windows.
- Added narrow-window and wide-window layout coverage.

### Changed

- Set the default local project version to `1.0.1` for the Android App and Android Studio plugin.
- Made `bridgeVersion` the single source of truth for local builds and releases.
- Changed release automation to read `bridgeVersion` while using a pushed SemVer tag as the release trigger and marker.

### Fixed

- Prevented polling from silently stopping when the scheduled polling task encounters an exception.
- Prevented Tool Window action buttons from being clipped when the available width is limited.

## [1.0.0] - 2026-07-14

### Added

- Added the Android text input application with multiline Unicode input, in-memory state management, and local persistence.
- Added the loopback HTTP server with health, text retrieval, and version-aware clear endpoints.
- Added the shared protocol module for Android App and plugin wire models.
- Added the Android Studio plugin Tool Window for device status, text refresh, copy, and Copy & Clear workflows.
- Added ADB discovery, device selection, port forwarding, HTTP connectivity checks, and reconnect handling.
- Added bounded ADB and HTTP operations, sanitized logging, lifecycle protection, and stability tests.
- Added CI verification and release automation for the Android debug APK and plugin distribution ZIP.

### Changed

- Consolidated the Android App, protocol module, and Android Studio plugin into one Gradle project.
- Restricted the Android HTTP server to `127.0.0.1:18080` and communication to the ADB-forwarded local channel.

[1.2.0]: https://github.com/eric-dev-wang/input-bridge/compare/v1.1.2...v1.2.0
[1.1.2]: https://github.com/eric-dev-wang/input-bridge/compare/v1.1.1...v1.1.2
[1.1.1]: https://github.com/eric-dev-wang/input-bridge/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/eric-dev-wang/input-bridge/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/eric-dev-wang/input-bridge/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/eric-dev-wang/input-bridge/releases/tag/v1.0.0
