# mockk_http

Flutter package for the [MockkHttp IntelliJ/Android Studio plugin](https://github.com/sergiydev09/MockkHttp). Intercept, record, debug, and mock HTTP responses in real-time — no proxy, no certificates, no root required.

> **Supported targets:** **Android emulators** and **iOS Simulators** work with zero config. Physical devices are supported by passing `host:` — see [Physical devices](#physical-devices).

## Requirements

- [MockkHttp IntelliJ plugin](https://github.com/sergiydev09/MockkHttp) installed in Android Studio or IntelliJ IDEA
- An Android emulator or a booted iOS Simulator on the same machine (physical devices: see below)

## Getting started

1. Install the [MockkHttp plugin](https://github.com/sergiydev09/MockkHttp) in your IDE
2. Add `mockk_http` to your Flutter app:

```yaml
dependencies:
  mockk_http: ^1.7.1
```

## Usage

### Option 1: Global HttpOverrides (recommended)

Intercepts ALL HTTP traffic from `dart:io`, `package:http`, and any library using `HttpClient`.

```dart
import 'package:mockk_http/mockk_http.dart';

void main() {
  MockkHttp.init(); // That's it!
  runApp(MyApp());
}
```

### Option 2: Dio Interceptor

For apps using [dio](https://pub.dev/packages/dio) as their HTTP library.

```dart
import 'package:mockk_http/mockk_http.dart';

final dio = Dio();
dio.interceptors.add(MockkHttpDioInterceptor());
```

### Debug-only initialization

Ensure MockkHttp is never active in release builds:

```dart
void main() {
  assert(() {
    MockkHttp.init();
    return true;
  }());
  runApp(MyApp());
}
```

## Physical devices

**iPhone:** pass your Mac's LAN IP (both devices on the same Wi-Fi) and add `NSLocalNetworkUsageDescription` to `ios/Runner/Info.plist`:

```dart
MockkHttp.init(host: '192.168.1.23'); // your Mac's LAN IP
```

**Physical Android device:** nothing to do with `mockk_http >= 1.7.0` and the MockkHttp IDE plugin 1.7.0+ — the plugin opens `adb reverse tcp:9876 tcp:9876` when it scans the device, and the package discovers the host by itself. On an older plugin, forward the port manually and pin the host:

```dart
MockkHttp.init(host: '127.0.0.1');
```

## Features

- **Recording mode** - Capture all HTTP traffic and inspect it in the plugin
- **Debug mode** - Pause requests and modify responses before they reach your app
- **Mock mode** - Auto-apply mock rules defined in the plugin
- **Zero config** - Package/bundle id auto-detected; the plugin host is discovered by PING on Android (device loopback via `adb reverse`, falling back to `10.0.2.2` on emulators) and is `127.0.0.1` on the iOS Simulator

## How it works

1. Your Flutter app makes HTTP requests as normal
2. MockkHttp intercepts the requests and communicates with the IntelliJ plugin via a TCP socket on port 9876
3. Depending on the mode, the plugin records, blocks for editing, or applies mock responses
4. The (optionally modified) response is returned to your app

No proxy configuration, certificate installation, or root access is needed.

## Request deduplication

Apps with multiple HTTP clients may produce duplicate captures. MockkHttp deduplicates by default (500ms window). Disable it for debugging:

```dart
MockkHttp.enableDeduplication = false;
```

## License

MIT License - see [LICENSE](LICENSE) for details.
