import 'dart:convert';
import 'dart:io';

import 'models.dart';

/// Low-level TCP client that communicates with the MockkHttp IntelliJ plugin.
///
/// Handles PING/PONG, CHECK_MOCK, and FLOW messages over raw TCP sockets
/// on port 9876 — the same protocol used by the Android library.
class MockkHttpPluginClient {
  final int port;

  /// Host where the plugin server is reachable. Resolved per platform when not
  /// overridden — see [resolveDefaultHost].
  final String host;

  /// Legacy alias kept for backwards compatibility (Android emulator host).
  @Deprecated('Use MockkHttpPluginClient(host: ...) or the resolved [host] field')
  static const String emulatorHost = '10.0.2.2';

  static const int _connectionTimeoutMs = 5000;
  static const int _readTimeoutMs = 60000;
  static const int _pingTimeoutMs = 500;
  static const int _pingCacheDurationMs = 5000;
  static const int _maxFailedAttempts = 3;

  /// After [_maxFailedAttempts] consecutive failures, wait this long before
  /// probing again (instead of giving up forever). This makes launch order
  /// irrelevant: an app started BEFORE pressing Start in the plugin will
  /// reconnect by itself once the server is up.
  static const int _failureCooldownMs = 15000;

  int _failedAttempts = 0;
  int _lastFailureTime = 0;
  int _lastPingTime = 0;
  bool _lastPingResult = false;

  MockkHttpPluginClient({
    this.port = 9876,
    String? host,
  }) : host = host ?? resolveDefaultHost();

  /// True when running inside the iOS Simulator.
  ///
  /// Two signals, because the environment depends on HOW the app was launched:
  /// - SIMULATOR_* env vars (present on SpringBoard/Xcode launches), and
  /// - the executable path: simulator apps always live under
  ///   `~/Library/Developer/CoreSimulator/`, physical-device apps never do.
  static bool get isIosSimulator =>
      Platform.isIOS &&
      (Platform.environment.containsKey('SIMULATOR_UDID') ||
          Platform.environment.containsKey('SIMULATOR_DEVICE_NAME') ||
          Platform.resolvedExecutable.contains('/CoreSimulator/'));

  /// Resolve the default plugin host for the current platform:
  /// - Android emulator: `10.0.2.2` (guest alias for the host's loopback)
  /// - iOS Simulator: `127.0.0.1` (the simulator shares the Mac's network
  ///   stack, so localhost IS the Mac — no forwarding needed)
  /// - Physical iOS device: no automatic route to the Mac exists; pass the
  ///   Mac's LAN IP via `MockkHttp.init(host: '192.168.x.x')`. Falls back to
  ///   `127.0.0.1`, which simply won't connect (interceptor stays dormant).
  static String resolveDefaultHost() {
    if (Platform.isAndroid) return '10.0.2.2';
    // iOS Simulator shares the Mac's loopback; physical devices need an
    // explicit host override (see MockkHttp.init(host:)).
    return '127.0.0.1';
  }

  String? _packageName;

  /// Set the package name to include in PING messages for app detection.
  void setPackageName(String? name) {
    _packageName = name;
  }

  /// Check if the plugin is listening. Result cached for 5 seconds.
  /// After repeated failures the client backs off for [_failureCooldownMs]
  /// and then probes again — it never gives up permanently.
  Future<bool> isPluginConnected() async {
    final now = DateTime.now().millisecondsSinceEpoch;

    if (_failedAttempts >= _maxFailedAttempts) {
      if (now - _lastFailureTime < _failureCooldownMs) return false;
      // Cooldown elapsed — allow a fresh probe (one attempt per cooldown).
      _failedAttempts = _maxFailedAttempts - 1;
    }

    if (now - _lastPingTime < _pingCacheDurationMs) return _lastPingResult;

    try {
      final socket = await Socket.connect(
        host,
        port,
        timeout: const Duration(milliseconds: _pingTimeoutMs),
      );

      // Send PING with package name so the plugin can detect this app
      final pingMessage = _packageName != null ? 'PING:$_packageName' : 'PING';
      socket.add(utf8.encode('$pingMessage\n'));
      await socket.flush();

      final response = await socket.first
          .timeout(const Duration(milliseconds: _pingTimeoutMs));
      final text = utf8.decode(response);
      final success = text.startsWith('PONG');

      await socket.close();

      if (success) _failedAttempts = 0;

      _lastPingTime = now;
      _lastPingResult = success;
      return success;
    } catch (_) {
      _failedAttempts++;
      _lastFailureTime = now;
      _lastPingTime = now;
      _lastPingResult = false;
      return false;
    }
  }

  /// Reset failure counter (e.g. when user manually retries).
  void resetFailures() {
    _failedAttempts = 0;
    _lastPingTime = 0;
    _lastPingResult = false;
  }

  /// Check if a mock exists for the given request.
  /// Returns [MockCheckResponse] with mode and optional mock data.
  Future<MockCheckResponse?> checkForMock(
    RequestData request, {
    String? packageName,
    String? projectId,
  }) async {
    try {
      final socket = await Socket.connect(
        host,
        port,
        timeout: const Duration(milliseconds: _connectionTimeoutMs),
      );

      final mockCheckRequest = MockCheckRequest(
        request: request,
        projectId: projectId,
        packageName: packageName,
      );

      final json = jsonEncode(mockCheckRequest.toJson());
      socket.add(utf8.encode('$json\n'));
      await socket.flush();

      final responseBytes = await socket.first
          .timeout(const Duration(milliseconds: _connectionTimeoutMs));
      final responseJson = utf8.decode(responseBytes).trim();

      await socket.close();

      if (responseJson.isEmpty || responseJson == 'PONG') return null;

      return MockCheckResponse.fromJson(
        jsonDecode(responseJson) as Map<String, dynamic>,
      );
    } catch (_) {
      return null;
    }
  }

  /// Send a flow to the plugin and wait for a modified response (Debug mode).
  /// Blocks until the user responds in the plugin dialog.
  Future<ModifiedResponseData?> sendFlowAndWait(FlowData flow) async {
    try {
      final socket = await Socket.connect(
        host,
        port,
        timeout: const Duration(milliseconds: _connectionTimeoutMs),
      );

      final json = jsonEncode(flow.toJson());
      socket.add(utf8.encode('$json\n'));
      await socket.flush();

      final responseBytes = await socket.first
          .timeout(const Duration(milliseconds: _readTimeoutMs));
      final responseJson = utf8.decode(responseBytes).trim();

      await socket.close();

      if (responseJson.isEmpty || responseJson == 'PONG') return null;

      return ModifiedResponseData.fromJson(
        jsonDecode(responseJson) as Map<String, dynamic>,
      );
    } catch (_) {
      return null;
    }
  }

  /// Send a flow to the plugin without waiting (Recording mode).
  Future<void> sendFlowAsync(FlowData flow) async {
    try {
      final socket = await Socket.connect(
        host,
        port,
        timeout: const Duration(milliseconds: _connectionTimeoutMs),
      );

      final json = jsonEncode(flow.toJson());
      socket.add(utf8.encode('$json\n'));
      await socket.flush();
      await socket.close();
    } catch (_) {
      // Fire-and-forget — don't disrupt the app if plugin is unavailable.
    }
  }
}
