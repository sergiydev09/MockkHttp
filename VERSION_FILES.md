# Version Files Reference

All files that contain version numbers and must be updated on each release.

## Core versions (build files)
- `build.gradle.kts` — line ~10: `version = "X.Y.Z"` (IntelliJ plugin version)
- `android-library/build.gradle.kts` — line ~13: `version = "X.Y.Z"`
- `gradle-plugin/build.gradle.kts` — line ~10: `version = "X.Y.Z"`
- `flutter-package/pubspec.yaml` — line ~6: `version: X.Y.Z`
- `flutter-package/lib/src/mockk_http_core.dart` — `static const String version = 'X.Y.Z'`

## Documentation and UI references
- `build.gradle.kts` — `changeNotes` HTML: version in setup instructions (`version "X.Y.Z"`)
- `src/main/kotlin/com/sergiy/dev/mockkhttp/ui/HelpPanel.kt` — multiple references:
  - Version banner text (`MockkHttp vX.Y.Z`)
  - Gradle plugin version in setup instructions (`version "X.Y.Z"`)
  - Flutter package version (`mockk_http: ^X.Y.Z`)
  - "Version X.Y.Z Stable Release" heading
- `flutter-package/README.md` — `mockk_http: ^X.Y.Z`
- `flutter-package/CHANGELOG.md` — new version section at top

## Code references
- `src/main/kotlin/com/sergiy/dev/mockkhttp/model/MockRuleModels.kt` — default `version` and `pluginVersion` fields
- `gradle-plugin/src/main/kotlin/com/sergiy/dev/mockkhttp/gradle/MockkHttpGradlePlugin.kt` — cache directory path

## How to update
Search for the old version: `grep -rn "X\.Y\.Z" --include="*.{kt,kts,dart,yaml,md,xml}" .`
