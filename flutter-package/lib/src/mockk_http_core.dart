import 'dart:async';
import 'dart:io';

import 'ios_bundle_info.dart';
import 'mockk_http_client.dart';
import 'http_overrides.dart';
import 'models.dart';

/// Core MockkHttp interceptor logic shared between dio and HttpOverrides.
///
/// Implements the same 4-mode flow as the Android library:
/// - RECORDING: capture traffic, don't block
/// - DEBUG: capture traffic, block for user modification
/// - MOCKK: return mock if available, no block
/// - MOCKK_DEBUG: return mock if available, block for user modification
class MockkHttpCore {
  final MockkHttpPluginClient client;
  final String? packageName;
  final String? projectId;

  /// Enable/disable interceptor globally.
  static bool isEnabled = true;

  /// Request deduplication to prevent duplicates from multiple HTTP clients.
  static bool enableDeduplication = true;
  static const int _dedupWindowMs = 500;
  static const int _cleanupIntervalMs = 10000;
  static final Map<String, int> _activeRequests = {};
  static int _lastCleanupTime = 0;

  MockkHttpCore({
    MockkHttpPluginClient? client,
    this.packageName,
    this.projectId,
  }) : client = client ?? MockkHttpPluginClient();

  /// Check if a request is a duplicate (within 500ms dedup window).
  bool isDuplicateRequest(String method, Uri uri) {
    if (!enableDeduplication) return false;

    final now = DateTime.now().millisecondsSinceEpoch;

    // Periodic cleanup
    if (now - _lastCleanupTime > _cleanupIntervalMs) {
      _cleanupOldRequests(now);
      _lastCleanupTime = now;
    }

    final key = '$method:${uri.scheme}://${uri.host}${uri.path}';
    final existing = _activeRequests[key];

    if (existing != null) {
      if (now - existing < _dedupWindowMs) return true;
      _activeRequests[key] = now;
    } else {
      _activeRequests[key] = now;
    }

    return false;
  }

  /// Mark a request as completed.
  void markRequestCompleted(String method, Uri uri) {
    if (!enableDeduplication) return;
    final key = '$method:${uri.scheme}://${uri.host}${uri.path}';
    _activeRequests.remove(key);
  }

  static void _cleanupOldRequests(int now) {
    _activeRequests.removeWhere(
      (_, timestamp) => now - timestamp > _dedupWindowMs * 2,
    );
  }

  /// Build a [RequestData] from method, URI, and headers.
  RequestData buildRequestData(
    String method,
    Uri uri,
    Map<String, String> headers, {
    String body = '',
  }) {
    return RequestData(
      method: method,
      url: uri.toString(),
      headers: headers,
      body: body,
    );
  }

  /// Build a [FlowData] from request and response info.
  FlowData buildFlowData({
    required RequestData request,
    required int statusCode,
    required Map<String, String> responseHeaders,
    required String responseBody,
    required int durationMs,
  }) {
    return FlowData(
      flowId: _generateId(),
      request: request,
      response: ResponseData(
        statusCode: statusCode,
        headers: responseHeaders,
        body: responseBody,
      ),
      timestamp: DateTime.now().millisecondsSinceEpoch,
      duration: durationMs,
      projectId: projectId,
      packageName: packageName,
    );
  }

  static String _generateId() {
    // Simple UUID v4-like ID
    final now = DateTime.now().millisecondsSinceEpoch;
    return '${now.toRadixString(16)}-${_randomHex(4)}-${_randomHex(4)}';
  }

  static String _randomHex(int length) {
    final random = DateTime.now().microsecondsSinceEpoch;
    return random.toRadixString(16).padLeft(length, '0').substring(0, length);
  }
}

/// Main entry point for MockkHttp initialization.
///
/// ```dart
/// void main() {
///   MockkHttp.init();  // That's it — package name auto-detected
///   runApp(MyApp());
/// }
/// ```
class MockkHttp {
  MockkHttp._();

  static const String version = '1.7.0';

  /// Initialize MockkHttp with global [HttpOverrides].
  ///
  /// This intercepts ALL HTTP traffic from `dart:io` HttpClient,
  /// including packages like `http` that use it internally.
  ///
  /// Call this before `runApp()`. Works on Android emulators, iOS Simulators
  /// (Apple Silicon or Intel), and physical iOS devices (with [host]).
  ///
  /// [port] - Plugin port (default: 9876)
  /// [packageName] - Override auto-detected package/bundle id (rarely needed)
  /// [host] - Override the plugin host. Not needed on emulators/simulators.
  ///   For a PHYSICAL iOS device pass your Mac's LAN IP, e.g.
  ///   `MockkHttp.init(host: '192.168.1.50')`, and add
  ///   `NSLocalNetworkUsageDescription` to the app's Info.plist (iOS 14+
  ///   shows a local-network permission prompt on first connection).
  static void init({
    int port = 9876,
    String? packageName,
    String? host,
    bool announce = true,
  }) {
    final resolvedPackage = packageName ?? autoDetectPackageName();

    final client = MockkHttpPluginClient(port: port, host: host);

    assert(() {
      print('┌──────────────────────────────────────────────');
      print('│ MockkHttp v$version (${_platformLabel()})');
      print('│ Package: ${resolvedPackage ?? "unknown"}');
      print('│ Host: ${client.host}:$port');
      if (Platform.isIOS &&
          !MockkHttpPluginClient.isIosSimulator &&
          host == null) {
        print('│ ⚠️ Physical iOS device without host: pass your');
        print('│    Mac\'s LAN IP: MockkHttp.init(host: "192.168.x.x")');
      }
      print('└──────────────────────────────────────────────');
      return true;
    }());

    client.setPackageName(resolvedPackage);
    final core = MockkHttpCore(client: client, packageName: resolvedPackage);
    MockkHttpOverrides.install(core);

    // Write marker file so the IntelliJ plugin can detect this app
    _writeMarkerFile(resolvedPackage);

    // Announce ourselves right away, then keep trying until the plugin answers.
    //
    // Until 1.7.0 the PING fired only from the request path, so an app that was running
    // but idle was invisible to the plugin's scan — and an app started BEFORE the plugin
    // stayed invisible until it happened to make a request. The retry also re-registers
    // the package after an IDE restart, which is the only thing that clears the plugin's
    // list of announced apps.
    if (announce) _startAnnouncing(client);
  }

  /// Cancels any announce loop from a previous [init] — mostly a convenience for tests.
  static void stopAnnouncing() {
    _announceTimer?.cancel();
    _announceTimer = null;
  }

  static Timer? _announceTimer;

  static void _startAnnouncing(MockkHttpPluginClient client) {
    stopAnnouncing();

    // Fire-and-forget: init() is called from main() and must never throw or block.
    unawaited(client.isPluginConnected());

    _announceTimer =
        Timer.periodic(const Duration(seconds: 20), (timer) async {
      if (client.hostConfirmed) {
        // The plugin knows about us; the request path keeps the registration alive.
        timer.cancel();
        _announceTimer = null;
        return;
      }
      await client.isPluginConnected();
    });
  }

  static String _platformLabel() {
    if (Platform.isAndroid) return 'Android';
    if (Platform.isIOS) {
      return MockkHttpPluginClient.isIosSimulator ? 'iOS Simulator' : 'iOS Device';
    }
    return Platform.operatingSystem;
  }

  /// Auto-detect the app identifier for the current platform.
  /// - Android: /proc/self/cmdline contains the package name.
  /// - iOS: the process environment carries `__CFBundleIdentifier`.
  static String? autoDetectPackageName() {
    if (Platform.isAndroid) {
      try {
        final cmdline = File('/proc/self/cmdline').readAsStringSync();
        // cmdline is null-terminated, take first segment
        final packageName = cmdline.split('\x00').first.trim();
        if (packageName.isNotEmpty && packageName.contains('.')) {
          return packageName;
        }
      } catch (_) {}
      return null;
    }

    if (Platform.isIOS) {
      // Env vars first (present on some launch paths)...
      final bundleId = Platform.environment['__CFBundleIdentifier'];
      if (bundleId != null && bundleId.isNotEmpty) return bundleId;

      final xpcName = Platform.environment['XPC_SERVICE_NAME'];
      if (xpcName != null && xpcName.startsWith('UIKitApplication:')) {
        final raw = xpcName.substring('UIKitApplication:'.length);
        final end = raw.indexOf('[');
        final id = (end > 0 ? raw.substring(0, end) : raw).trim();
        if (id.isNotEmpty) return id;
      }

      // ...but on recent iOS runtimes Platform.environment is EMPTY, so the
      // reliable source is the app's own Info.plist (next to the executable).
      return readIosBundleIdentifier();
    }

    return null;
  }

  /// Name of the in-sandbox marker, read by the plugin via `run-as`.
  static const String markerFileName = 'mockk_http.marker';

  /// Write a marker file so the IntelliJ plugin can detect this app.
  ///
  /// - Android: TWO locations, because neither works everywhere.
  ///   * `{app temp dir}/mockk_http.marker` — inside the app's own sandbox
  ///     (Dart's [Directory.systemTemp] is the app cache dir on Android). The
  ///     plugin reads it with `adb shell run-as {pkg} cat cache/...`, which
  ///     works on production devices for any debuggable build. This is the
  ///     only one that works on a physical phone.
  ///   * `/data/local/tmp/mockk_http_{pkg}` — kept for emulators. On a real
  ///     device that directory is `drwxrwx--x shell shell`, so an ordinary app
  ///     cannot write there at all; the attempt fails silently by design.
  /// - iOS Simulator: `{data container}/Documents/.mockk_http` — the plugin
  ///   finds it via `xcrun simctl get_app_container {udid} {bundleid} data`.
  static void _writeMarkerFile(String? packageName) {
    if (packageName == null) return;

    if (Platform.isAndroid) {
      // In-sandbox marker: the only one that works on real hardware.
      _writeAndroidSandboxMarker(packageName, 'flutter:$version:$packageName');
    }

    try {
      if (Platform.isAndroid) {
        final marker = File('/data/local/tmp/mockk_http_$packageName');
        marker.writeAsStringSync('flutter');
      } else if (Platform.isIOS) {
        final home = Platform.environment['HOME'];
        if (home == null || home.isEmpty) return;
        final marker = File('$home/Documents/.mockk_http');
        marker.writeAsStringSync('flutter:$packageName');
      }
    } catch (_) {
      // Non-critical — detection falls back to PING announcements
    }
  }

  /// Write [payload] inside the app's own data directory, where `run-as` can read it.
  ///
  /// The path is derived from `/proc/self/status` rather than taken from
  /// [Directory.systemTemp]: Android sets no `TMPDIR` for app processes (verified on a
  /// Pixel 7a — `TMPDIR` is empty), so `systemTemp` resolves to `/tmp`, which does not
  /// exist on Android. The app's uid encodes the Android user: uid 10312 → user 0 →
  /// `/data/user/0/<pkg>/`.
  static void _writeAndroidSandboxMarker(String packageName, String payload) {
    try {
      final status = File('/proc/self/status').readAsStringSync();
      final uid = int.parse(RegExp(r'Uid:\s+(\d+)').firstMatch(status)!.group(1)!);
      final userId = uid ~/ 100000; // 0 normally, 10+ on a work profile

      for (final base in [
        '/data/user/$userId/$packageName',
        '/data/data/$packageName',
      ]) {
        for (final dir in ['cache', 'files']) {
          try {
            File('$base/$dir/$markerFileName').writeAsStringSync(payload);
            return;
          } catch (_) {
            // Try the next location.
          }
        }
      }
    } catch (_) {
      // Non-critical — detection falls back to PING / on-device APK inspection.
    }
  }

  /// Disable MockkHttp globally.
  static void disable() {
    MockkHttpCore.isEnabled = false;
  }

  /// Enable MockkHttp globally.
  static void enable() {
    MockkHttpCore.isEnabled = true;
  }

  /// Enable/disable request deduplication.
  static set enableDeduplication(bool value) {
    MockkHttpCore.enableDeduplication = value;
  }
}
