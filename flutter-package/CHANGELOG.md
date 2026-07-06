## 1.6.0

- Feature: iOS support. The plugin host is now resolved per platform — Android emulator (`10.0.2.2`), iOS Simulator (`127.0.0.1`, the simulator shares the Mac's network stack, zero config), physical iOS device (pass your Mac's LAN IP via `MockkHttp.init(host: ...)` / `MockkHttpDioInterceptor(host: ...)` and add `NSLocalNetworkUsageDescription` to Info.plist).
- Feature: bundle id auto-detection on iOS via the app's own `Info.plist` (pure-Dart binary-plist reader) — works even though `Platform.environment` is empty on recent iOS runtimes. Env vars are still tried first.
- Feature: simulator detection via the executable path (`/CoreSimulator/`), independent of environment variables.
- Fix: the client now retries the plugin connection after a 15s cooldown instead of giving up forever, so launch order (app vs plugin Start) no longer matters.
- Feature: marker file in the app's data container on the iOS Simulator so the IDE plugin can detect MockkHttp-enabled apps.

## 1.5.4

- Feature: Capture the request body on the `HttpOverrides` path (`package:http` / raw `HttpClient`) so POST/PUT payloads are shown in the plugin Inspector. The `dio` interceptor already captured it. Bounded to 5MB; binary/oversized bodies show a placeholder.

## 1.5.3

- Fix: Calls in Mockk mode now appear in the plugin Inspector list (HttpOverrides + dio interceptors). Previously the mock was applied locally but the flow was never sent to the plugin.

## 1.5.2

- Fix: HTTP response body now returns `Uint8List` instead of `List<int>`, fixing compatibility with `package:http` and other libraries that cast response bytes

## 1.5.1

- Removed `host` parameter from `MockkHttp.init()` and `MockkHttpDioInterceptor()` — host is always auto-detected (10.0.2.2 for Android emulator)
- Clarified that the package currently only works on Android emulators
- Updated documentation and examples

## 1.5.0

- Initial pub.dev release
- Global `HttpOverrides` interception for `dart:io`, `package:http`, and any library using `HttpClient`
- Dio interceptor (`MockkHttpDioInterceptor`) for apps using dio
- Recording mode: capture HTTP traffic without blocking
- Debug mode: pause and modify responses in real-time via the IntelliJ plugin
- Mock mode: auto-apply mock rules from the plugin
- Request deduplication to prevent duplicates from multiple HTTP clients
- Auto-detection of package name on Android
- Plugin detection via PING handshake (no root or proxy required)
