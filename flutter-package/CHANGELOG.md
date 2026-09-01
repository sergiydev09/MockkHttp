## 1.7.1

- **Fix: replies from the plugin were silently truncated.** The client read a reply with
  `socket.first`, which yields only the FIRST data event — so anything that did not arrive in a
  single TCP read (a mocked JSON body of a few KB is enough) was cut short. The truncated text
  failed to parse, the error was swallowed, and the app fell back to the original response with
  no trace anywhere: a mock or a Debug edit simply did not apply, at random, depending on size.
  Replies are now reassembled until the plugin's newline, so a body of any size arrives intact —
  including one split mid-character, which previously also corrupted the surrounding text.
  Affects mocked responses, Debug-mode edits and `CHECK_MOCK` alike.

## 1.7.0

- **Fix: physical Android devices now work.** The plugin host was hardcoded to `10.0.2.2` on
  Android — a QEMU-only alias that routes nowhere on a real phone — so an app on physical
  hardware could never reach the plugin, whether or not `adb reverse` was set up. The host is
  now **discovered, not guessed**: the client tries `127.0.0.1` (the phone's loopback, tunnelled
  by the plugin's `adb reverse`) and then `10.0.2.2` (emulator), keeping whichever answers PONG.
  Passing `host:` explicitly still overrides everything, and iOS behaviour is unchanged.
- **Fix: the marker file the plugin looks for is now written where it can actually be read.**
  It used to go to `/data/local/tmp/`, which is `drwxrwx--x shell:shell` on a production device
  — an app simply cannot write there. It is now also written inside the app's own sandbox
  (path derived from `/proc/self/status`), where the plugin reads it with `adb shell run-as`.
- **Feature: the app announces itself at `init()`** and keeps retrying every 20 s until the
  plugin answers. Previously the PING only fired when the app made an HTTP request, so a
  running-but-idle app was invisible to the plugin's scan — and an app started before the
  plugin stayed invisible. Opt out with `MockkHttp.init(announce: false)`.
- Requires the MockkHttp IntelliJ plugin 1.7.0 or newer for automatic physical-device setup.

## 1.6.1

- Docs: README now documents the real platform support — Android emulators and iOS Simulators with zero config, plus a new "Physical devices" section (iPhone via `MockkHttp.init(host: ...)` + `NSLocalNetworkUsageDescription`; Android via `adb reverse` + `host: '127.0.0.1'`).
- No functional changes. Version aligned with the MockkHttp IntelliJ plugin 1.6.1 release.

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
