/// A stand-in for the IntelliJ plugin on port 9876, plus the small helpers the capture
/// tests share. Extracted from idle_mode_test.dart so the duplicate-capture tests can drive
/// the same fake instead of a second copy of it.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:mockk_http/mockk_http.dart';

class FakePlugin {
  FakePlugin._(this._server, this._checkReply);

  final ServerSocket _server;
  final String _checkReply;

  /// Message types received, in order. PINGs are not messages and are counted apart.
  final List<String> messages = [];
  int pings = 0;

  /// The last flow that arrived, decoded, so a test can look at what was captured.
  Map<String, dynamic>? lastFlow;

  /// The last CHECK_MOCK that arrived, decoded.
  Map<String, dynamic>? lastCheck;

  /// The `stats` block of the last message of any kind, or null.
  Map<String, dynamic>? get lastStats {
    final last = lastFlow ?? lastCheck;
    final client = last?['client'] as Map<String, dynamic>?;
    return client?['stats'] as Map<String, dynamic>?;
  }

  int get port => _server.port;

  int get flowCount => messages.where((type) => type != 'CHECK_MOCK').length;

  int get checkCount => messages.where((type) => type == 'CHECK_MOCK').length;

  /// [mockCheckReply] is the raw JSON object the plugin answers to a CHECK_MOCK.
  /// Defaults to what the plugin now sends when nobody has pressed Start.
  static Future<FakePlugin> start({
    Map<String, dynamic> mockCheckReply = const {
      'hasMock': false,
      'mode': 'IDLE'
    },
  }) async {
    final server = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
    final plugin = FakePlugin._(server, jsonEncode(mockCheckReply));
    server.listen(plugin._handle);
    return plugin;
  }

  Future<void> _handle(Socket socket) async {
    try {
      final line =
          await utf8.decoder.bind(socket).transform(const LineSplitter()).first;

      if (line.startsWith('PING')) {
        pings++;
        socket.add(utf8.encode('PONG\n'));
        await socket.flush();
        await socket.close();
        return;
      }

      final decoded = jsonDecode(line) as Map<String, dynamic>;
      final type = decoded['type'] as String? ?? 'FLOW';
      messages.add(type);
      if (type == 'CHECK_MOCK') {
        lastCheck = decoded;
      } else {
        lastFlow = decoded;
      }

      // A FLOW is answered with "no modifications", exactly like the real server.
      socket.add(utf8.encode(type == 'CHECK_MOCK' ? '$_checkReply\n' : '{}\n'));
      await socket.flush();
      await socket.close();
    } catch (_) {
      // A client that hangs up mid-message is not itself a test failure; the
      // assertions on [messages] are what decide.
    }
  }

  Future<void> close() => _server.close();
}

/// An origin server the app under test actually talks to.
Future<HttpServer> startOrigin(
    void Function(HttpRequest request) handle) async {
  final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
  server.listen(handle);
  return server;
}

HttpClient clientFor(FakePlugin plugin) {
  final core = MockkHttpCore(
    client: MockkHttpPluginClient(port: plugin.port),
    packageName: 'com.test.app',
  );
  return MockkHttpOverrides(core).createHttpClient(null);
}

/// Give a fire-and-forget flow socket time to ARRIVE, so "none arrived" means it.
Future<void> settle() =>
    Future<void>.delayed(const Duration(milliseconds: 300));

/// Wait until a flow shows up, or give up after [timeout].
Future<void> awaitFlow(
  FakePlugin plugin, {
  Duration timeout = const Duration(seconds: 3),
}) async {
  final deadline = DateTime.now().add(timeout);
  while (plugin.flowCount == 0 && DateTime.now().isBefore(deadline)) {
    await Future<void>.delayed(const Duration(milliseconds: 20));
  }
}
