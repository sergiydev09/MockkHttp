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
