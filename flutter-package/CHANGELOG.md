## 1.8.0

- **IDLE mode: nothing to pay while nobody is capturing.** The MockkHttp plugin 1.8.0 answers
  `IDLE` when no capture session owns this app's traffic (nobody pressed Start, or the session's
  package filter excludes the app). The package now passes such requests through untouched: no
  response buffering, no flow, no second socket. Previously the fallback for "no mode" was
  RECORDING, so an idle IDE cost every request a full body read plus a socket to ship a flow the
  plugin dropped on arrival. Older plugins keep answering RECORDING and keep being recorded.
- **Request bodies are serialised only once the plugin has asked for them.** The mock check is
  matched on method + URL, so encoding up to 5 MB of payload before it was pure waste whenever the
  answer turned out to be IDLE.
- **Fix: two requests started in the same millisecond shared a flow id.** Both "random" halves of
  the id came from the clock, so they were effectively constant. A duplicated id broke flow lookups
  and `from_flow_id`, the documented way to turn a captured call into a mock rule. Ids now carry
  real entropy.
- **Fix: identical requests are no longer dropped.** The 500 ms deduplication window discarded
  a genuine second request to the same URL — two screens asking for the same forecast, an
  immediate retry — inside the app, with no trace anywhere: the plugin's log was missing
  requests the app really made. The window existed for one request seen by two layers (a `dio`
  interceptor on top of the global `HttpOverrides`), which is a question of identity, not of
  time. Give the interceptor its Dio — `MockkHttpDioInterceptor(dio: dio)` — and it wraps the
  Dio's adapter to hand the layer underneath a claim through a Dart zone; the request itself
  goes down exactly as the app wrote it, on any adapter, and the inner layer steps aside for
  exactly that request. Without `dio:` the interceptor stands back whenever the global override
  is active. Two genuine requests are two flows. `MockkHttp.enableDeduplication = false` still
  turns the coordination off entirely.
- **The package reports its own numbers.** Every message to the plugin now carries `client`:
  library, version, platform, how the two layers coordinate, and counters — flows sent (by
  layer), claims made, passes yielded, claims withdrawn, times the dio interceptor stood back.
  The plugin (1.8.0) shows the latest under `client` on `status` and on the flow listing, so
  "one request, one flow" can be checked from outside instead of trusted. `run_id` and
  `started_at` anchor the counters to the app run; `claims_not_carried`, `claims_untaken_beneath`,
  `claims_not_fetched`, `wrapper_replaced` and `claims_evicted` make the remaining ways a request
  could be counted twice visible instead of silent — by what the claim recorded, not by what the
  adapter looks like afterwards. A request the mock answers makes no claim at all, and the one
  warning the package prints is said when a request has actually lost its claim, not when an
  adapter is merely replaced — and "actually" needs two witnesses: that `Dio`'s own adapter
  replaced while the request was in flight, and the `dart:io` layer having seen the request go
  past unclaimed (it remembers those; `beneath_witness_evicted` says if that memory overflowed).
  Every report also carries a `seq`, so the plugin never lets a message overtaken on the wire
  roll the counters back. When the adapter was replaced and nothing beneath matched, the package
  says `claims_unresolved` rather than "nobody saw it" — a signing adapter that rewrites the URL
  reports the request twice under a name the witness cannot match. `claims_pending` shows the
  claims alive right now: a request a later interceptor answered with `handler.resolve(response)`
  at its default flag never comes back to this layer (see the README), and this is where it shows.
- **Fix: installing the overrides twice captured every request twice.** `MockkHttp.init()`
  called from two places, or a test installing on top of an app that already did, wrapped one
  `HttpClient` in two MockkHttp layers. A second install now replaces the first.
- **Fix: a mocked answer served through the `dio` interceptor was reported twice.** The
  interceptor sent the flow from `onRequest` and then again from its own `onResponse`.

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
