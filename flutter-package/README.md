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
  mockk_http: ^1.8.0
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
dio.interceptors.add(MockkHttpDioInterceptor(dio: dio));
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

## One request, one flow

A `dio` interceptor sits on top of the global `HttpOverrides` (dio's default adapter builds its
`HttpClient` through `HttpOverrides.global`), so with both installed the same request goes past
both. Give the interceptor its `Dio` — `MockkHttpDioInterceptor(dio: dio)` — and it wraps the
Dio's adapter to tell the layer underneath which request it has already captured, through a Dart
zone: the request itself, URL, headers and body, goes down exactly as your app wrote it. Two
genuine requests to the same URL — a retry, two screens loading the same data — are two flows;
nothing is dropped on a timer. Without the `dio:` argument the interceptor stands back whenever the
global override is active, so nothing is reported twice either; pass it when you use an adapter
that bypasses `dart:io` (native adapters), because then this interceptor is the only layer that
can see the request. Switch the coordination off if you want every layer to report everything it
sees:

```dart
MockkHttp.enableDeduplication = false;
```

Configure `dio.httpClientAdapter` (pinning, a proxy, timeouts) **before** sending traffic. The
interceptor puts its wrapper back at the start of every request, so an adapter set up earlier —
or set again on every request by an interceptor of your own — is never a problem. An adapter
swapped while a request is in flight cannot be told to step aside, and that one request is
reported twice. The report counts it in `claims_not_carried` only when it actually happened, on
two witnesses: that Dio's adapter was replaced while the request was in flight, and the
`dart:io` layer saw that very request (same method and URL, after the claim was made) go past
without its claim — and a debug print says so once, when that request ends. When the adapter
was replaced but nothing beneath matched, the package does not know whether the request was
answered above the adapter, sent through one that bypasses `dart:io`, or fetched under a URL the
new adapter rewrote (a signing or discovery adapter) and reported twice all the same: that is
`claims_unresolved`, said once, never passed off as "nobody saw it". A request your mock answers
never goes down and makes no claim; one whose Dio kept its adapter and that never reached it —
answered or cancelled by a later interceptor — is `claims_not_fetched`. `wrapper_replaced`
counts the replacements themselves, which are harmless on their own.

Two limits of that bookkeeping, so you can read the counters honestly. The witness beneath is
matched by identity, so a request from another stack (`package:http`, a bare `HttpClient`) to
the *same* method and URL, while a dio claim to it is alive and that Dio's adapter has been
replaced, answers for it: `claims_not_carried` counts one too many and the debug print names an
adapter you did configure in time — that takes an in-flight swap, a claim that never reached
`dart:io` (answered above, or a native adapter), and an unrelated identical request in the same
window, and it is not pursued. And the `dart:io` layer remembers unclaimed passes only while a
dio claim to that same method and URL is alive, in a bounded list: `beneath_witness_evicted`
counts what that list had to drop, and while it is not zero a lost claim could have gone
unwitnessed.

One thing `dio` itself hides. A request interceptor placed *after* `MockkHttpDioInterceptor`
that answers a request with `handler.resolve(response)` — a cache, with the flag at its default
— runs no response interceptor at all, so MockkHttp never sees that request end: it produces no
flow, and its claim stays alive, which `claims_pending` shows (a number that stays put with
nothing in flight is exactly this). Put such an interceptor *before* MockkHttp's, so a cache hit
is simply not traffic, or resolve with `callFollowingResponseInterceptor: true` and the request
is reported like any other.

Every message the package sends carries a `client` report — library, version, platform and its
counters (flows sent by layer, claims made, passes yielded, claims withdrawn, times the dio
interceptor stood back) — which the plugin shows under `client` on `status` and on the flow
listing. That is how "one request, one flow" is checked from outside rather than trusted.

## License

MIT License - see [LICENSE](LICENSE) for details.
