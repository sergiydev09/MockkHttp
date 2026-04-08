# mockk_http

Flutter package for the [MockkHttp IntelliJ/Android Studio plugin](https://github.com/sergiydev09/MockkHttp). Intercept, record, debug, and mock HTTP responses in real-time — no proxy, no certificates, no root required.

> **Note:** Currently only works on **Android emulators**. Physical device and iOS support are planned for future releases.

## Requirements

- [MockkHttp IntelliJ plugin](https://github.com/sergiydev09/MockkHttp) installed in Android Studio or IntelliJ IDEA
- Android emulator running on the same machine

## Getting started

1. Install the [MockkHttp plugin](https://github.com/sergiydev09/MockkHttp) in your IDE
2. Add `mockk_http` to your Flutter app:

```yaml
dependencies:
  mockk_http: ^1.5.1
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

## Features

- **Recording mode** - Capture all HTTP traffic and inspect it in the plugin
- **Debug mode** - Pause requests and modify responses before they reach your app
- **Mock mode** - Auto-apply mock rules defined in the plugin
- **Zero config** - Package name and host are auto-detected

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
