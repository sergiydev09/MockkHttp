import 'dart:convert';
import 'dart:io';

import 'models.dart';

/// Low-level TCP client that communicates with the MockkHttp IntelliJ plugin.
///
/// Handles PING/PONG, CHECK_MOCK, and FLOW messages over raw TCP sockets
/// on port 9876 — the same protocol used by the Android library.
class MockkHttpPluginClient {
  final int port;

  /// Fixed host for Android emulator (10.0.2.2 is the emulator's alias for host loopback).
  static const String emulatorHost = '10.0.2.2';

  static const int _connectionTimeoutMs = 5000;
  static const int _readTimeoutMs = 60000;
  static const int _pingTimeoutMs = 500;
  static const int _pingCacheDurationMs = 5000;
  static const int _maxFailedAttempts = 3;

  int _failedAttempts = 0;
  int _lastPingTime = 0;
  bool _lastPingResult = false;

  MockkHttpPluginClient({
    this.port = 9876,
  });

  String? _packageName;

  /// Set the package name to include in PING messages for app detection.
  void setPackageName(String? name) {
    _packageName = name;
  }

  /// Check if the plugin is listening. Result cached for 5 seconds.
  Future<bool> isPluginConnected() async {
    if (_failedAttempts >= _maxFailedAttempts) return false;

    final now = DateTime.now().millisecondsSinceEpoch;
    if (now - _lastPingTime < _pingCacheDurationMs) return _lastPingResult;

    try {
      final socket = await Socket.connect(
        emulatorHost,
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
        emulatorHost,
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
        emulatorHost,
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
        emulatorHost,
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
