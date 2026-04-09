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

  static const String version = '1.5.2';

  /// Initialize MockkHttp with global [HttpOverrides].
  ///
  /// This intercepts ALL HTTP traffic from `dart:io` HttpClient,
  /// including packages like `http` that use it internally.
  ///
  /// Call this before `runApp()`. Only works on Android emulators.
  ///
  /// [port] - Plugin port (default: 9876)
  /// [packageName] - Override auto-detected package name (rarely needed)
  static void init({
    int port = 9876,
    String? packageName,
  }) {
    final resolvedPackage = packageName ?? autoDetectPackageName();

    assert(() {
      print('┌──────────────────────────────────────────────');
      print('│ MockkHttp v$version');
      print('│ Package: ${resolvedPackage ?? "unknown"}');
      print('│ Host: ${MockkHttpPluginClient.emulatorHost}:$port');
      print('└──────────────────────────────────────────────');
      return true;
    }());

    final client = MockkHttpPluginClient(port: port);
    client.setPackageName(resolvedPackage);
    final core = MockkHttpCore(client: client, packageName: resolvedPackage);
    MockkHttpOverrides.install(core);

    // Write marker file so the IntelliJ plugin can detect this app
    _writeMarkerFile(resolvedPackage);
  }

  /// Auto-detect package name from the Android process.
  /// On Android, /proc/self/cmdline contains the package name.
  static String? autoDetectPackageName() {
    if (!Platform.isAndroid) return null;

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

  /// Write a marker file to /data/local/tmp/ so the plugin's AppManager
  /// can detect Flutter apps with MockkHttp via `adb shell ls`.
  /// This is writable by any app and readable by ADB.
  static void _writeMarkerFile(String? packageName) {
    if (packageName == null || !Platform.isAndroid) return;

    try {
      final marker = File('/data/local/tmp/mockk_http_$packageName');
      marker.writeAsStringSync('flutter');
    } catch (_) {
      // Non-critical — detection falls back to grep
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
