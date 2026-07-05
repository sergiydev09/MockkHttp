import 'dart:io';

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

  static const String version = '1.6.0-dev.1';

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
      final bundleId = Platform.environment['__CFBundleIdentifier'];
      if (bundleId != null && bundleId.isNotEmpty) return bundleId;
      return null;
    }

    return null;
  }

  /// Write a marker file so the IntelliJ plugin can detect this app.
  /// - Android: `/data/local/tmp/mockk_http_{pkg}` (readable via `adb shell ls`).
  /// - iOS Simulator: `{data container}/Documents/.mockk_http` — the plugin
  ///   finds it via `xcrun simctl get_app_container {udid} {bundleid} data`.
  static void _writeMarkerFile(String? packageName) {
    if (packageName == null) return;

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
