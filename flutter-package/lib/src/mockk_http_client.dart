import 'dart:convert';
import 'dart:io';

import 'models.dart';

/// Low-level TCP client that communicates with the MockkHttp IntelliJ plugin.
///
/// Handles PING/PONG, CHECK_MOCK, and FLOW messages over raw TCP sockets
/// on port 9876 — the same protocol used by the Android library.
class MockkHttpPluginClient {
  final int port;

  /// Host where the plugin server is currently believed to be reachable.
  ///
  /// On Android this is discovered, not guessed: see [_hostCandidates]. Until the
  /// first successful PING it holds the first candidate.
  String _resolvedHost;

  /// Non-null when the caller passed `host:` explicitly — then no discovery happens.
  final String? _explicitHost;

  /// True once a PONG proved [_resolvedHost] is the right one.
  bool _hostConfirmed = false;

  String get host => _resolvedHost;

  /// True once a PONG proved [host] is right. Until then the client is still
  /// discovering, and callers may want to keep announcing.
  bool get hostConfirmed => _hostConfirmed;

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
  })  : _explicitHost = host,
        _resolvedHost = host ?? _defaultHostCandidates().first;

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

  /// Hosts to try, in order, when no explicit host was given.
  ///
  /// Android has TWO valid answers and which one applies cannot be told apart
  /// reliably from pure Dart:
  /// - `127.0.0.1` — a PHYSICAL device, reached through `adb reverse tcp:9876`
  ///   (the plugin opens that tunnel before scanning). This was missing until
  ///   1.7.0, which is why physical devices never connected: `10.0.2.2` is a
  ///   QEMU-only alias that simply times out on real hardware.
  /// - `10.0.2.2` — the Android emulator's alias for the host's loopback.
  ///
  /// Rather than guess from `/proc/cpuinfo` or qemu device nodes (signals that
  /// vary by OEM, API level and app sandbox, and fail silently when wrong), the
  /// client simply tries each candidate once and keeps whichever answers PONG.
  /// A wrong candidate costs one refused connection, not a wrong verdict.
  ///
  /// iOS: the Simulator shares the Mac's loopback, so `127.0.0.1` IS the Mac.
  /// A physical iPhone has no automatic route and needs an explicit host —
  /// `MockkHttp.init(host: '192.168.x.x')`.
  static List<String> _defaultHostCandidates() {
    if (Platform.isAndroid) return const ['127.0.0.1', '10.0.2.2'];
    return const ['127.0.0.1'];
  }

  /// The candidates this client will actually try. An explicit host is taken as
  /// final — the caller knows something we cannot discover (e.g. a LAN IP).
  List<String> get _hostCandidates =>
      _explicitHost != null ? [_explicitHost!] : _defaultHostCandidates();

  /// The default host for the current platform.
  ///
  /// Kept for backwards compatibility. Prefer the resolved [host] field: on
  /// Android this returns only the FIRST candidate, and the real host is
  /// discovered by [isPluginConnected].
  static String resolveDefaultHost() => _defaultHostCandidates().first;

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

    // Once a host is confirmed, stop probing the others. If it later stops
    // answering, fall back to trying them all again — the device may have moved
    // between an emulator and a phone within the same session on a shared client.
    final candidates = _hostConfirmed ? [_resolvedHost] : _hostCandidates;

    for (final candidate in candidates) {
      if (await _ping(candidate)) {
        _resolvedHost = candidate;
        _hostConfirmed = true;
        _failedAttempts = 0;
        _lastPingTime = now;
        _lastPingResult = true;
        return true;
      }
    }

    // Nothing answered: forget the confirmation so the next probe (after the
    // cooldown) tries every candidate again instead of retrying a dead host.
    _hostConfirmed = false;
    _failedAttempts++;
    _lastFailureTime = now;
    _lastPingTime = now;
    _lastPingResult = false;
    return false;
  }

  /// One PING/PONG round trip against [candidate]. Never throws.
  Future<bool> _ping(String candidate) async {
    Socket? socket;
    try {
      socket = await Socket.connect(
        candidate,
        port,
        timeout: const Duration(milliseconds: _pingTimeoutMs),
      );

      // Send PING with package name so the plugin can detect this app
      final pingMessage = _packageName != null ? 'PING:$_packageName' : 'PING';
      socket.add(utf8.encode('$pingMessage\n'));
      await socket.flush();

      final response = await socket.first
          .timeout(const Duration(milliseconds: _pingTimeoutMs));
      return utf8.decode(response).startsWith('PONG');
    } catch (_) {
      return false;
    } finally {
      try {
        await socket?.close();
      } catch (_) {
        // Closing a socket that never connected is not an error worth surfacing.
      }
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
