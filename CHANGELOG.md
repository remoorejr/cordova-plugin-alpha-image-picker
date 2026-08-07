# Changelog

All notable changes to this project are documented in this file.

## [2.4.0] - 2026-08-07

### Added
- Android 13+ media permission support (`READ_MEDIA_IMAGES`).
- iOS support for modern Photos authorization behavior, including Limited access handling.
- Optional iOS Info.plist key wiring for `NSPhotoLibraryAddUsageDescription`.

### Changed
- Migrated Android codebase from legacy `android.support.*` to `androidx.*`.
- Updated Android dependency strategy to AndroidX AppCompat.
- Updated plugin compatibility targets for modern Cordova platform versions.
- Improved permission request flow so plugin waits for user authorization result before continuing.
- Switched iOS image loading from bundled-resource API usage to file-path-safe loading for selected assets.
- Modernized image decode/scaling flow on Android to avoid direct reliance on deprecated `MediaStore.Images.Media.DATA`.

### Fixed
- Android thumbnail rotation issue in `ImageFetcher` where rotation matrix was not applied.
- Runtime failures when selecting images on newer Android scoped-storage devices.
- iOS image conversion failures caused by incorrect image loading method.

### Removed
- Legacy Android support-library dependency (`com.android.support:appcompat-v7`).
- Legacy AndroidX adapter bridge dependency requirement.

---

## [2.3.9] - Previous release
- Legacy baseline behavior.